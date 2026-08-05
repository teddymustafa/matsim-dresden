package org.matsim.run.scenarios;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.MATSimApplication;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.util.*;

/**
 * Dresden scenario with an aeroTaxi mode.
 *
 * aeroTaxi is a TELEPORTED mode that is routed on a network: it is added to
 * routing.networkModes but NOT to qsim.mainModes. MATSim computes a route on a
 * dedicated, mode-exclusive air network and then teleports the agent along it.
 * The air network is disjoint from the road network, so agents must walk to a
 * vertiport -- which is what makes vertiport siting the binding constraint.
 *
 * Everything lives in this one class:
 *   prepareConfig    -- register the mode for routing, scoring and mode choice
 *   prepareScenario  -- build the vertiport / flight-link network
 *   prepareControler -- bind the base-fare handler
 *
 * Run (config file and 1 pct sample are hardcoded in main; iterations, run id
 * and output directory are set in prepareConfig):
 *   IntelliJ program arguments:  run --iterations 0
 *   Command line:  java -Xmx11g -cp matsim-dresden.jar \
 *                       org.matsim.run.scenarios.RunAeroTaxi run --iterations 0
 *
 * TODO before the first run: copy the @MATSimApplication.Prepare and
 * @MATSimApplication.Analysis annotation blocks from DresdenScenario onto this
 * class. Java does not inherit annotations, so without them the scenario's
 * preparation steps and the automatic SimWrapper dashboards are lost.
 *
 * MATSim UE Planning, Summer Semester 2026
 */
public class DresdenAeroTaxiScenario extends DresdenScenario {

	private static final Logger log = LogManager.getLogger(DresdenAeroTaxiScenario.class);

	// ---- policy parameters (justify every one of these in section 2) ----
	private static final String MODE = "aeroTaxi";
	private static final String INTERACTION = MODE + " interaction";

	/**
	 * Vertiport set P2 (12 sites), coordinates in EPSG:25832 (ETRS89 / UTM 32N).
	 *
	 * Positions are approximate (few hundred metres), which is adequate given
	 * the 2.5 km walk catchment, but refine them from the siting script output
	 * before the final run.
	 */
	private static final Map<String, Coord> VERTIPORTS = new LinkedHashMap<>();

	static {
		VERTIPORTS.put("HBF", new Coord(831692.0, 5664935.0)); // Dresden Hauptbahnhof
		VERTIPORTS.put("APT", new Coord(833474.9, 5675405.8)); // Airport Klotzsche
		VERTIPORTS.put("NST", new Coord(832088.6, 5667839.5)); // Neustadt Bahnhof
		VERTIPORTS.put("ALT", new Coord(831402.0, 5667036.5)); // Altstadt / Kongresszentrum
		VERTIPORTS.put("TUD", new Coord(831413.5, 5663578.1)); // TU Dresden campus
		VERTIPORTS.put("SIL", new Coord(832274.8, 5673118.5)); // Klotzsche / Silicon Saxony
		VERTIPORTS.put("NIC", new Coord(836328.2, 5659432.6)); // Nickern Kaufpark P+R
		VERTIPORTS.put("PIR", new Coord(846832.0, 5657225.6)); // Pirna
		VERTIPORTS.put("FRE", new Coord(826221.2, 5660011.3)); // Freital
		VERTIPORTS.put("RDB", new Coord(826168.6, 5671946.3)); // Radebeul
		VERTIPORTS.put("MEI", new Coord(812776.5, 5677360.8)); // Meissen
		VERTIPORTS.put("RDG", new Coord(843778.7, 5674433.3)); // Radeberg
	}

	private static final double CRUISE_SPEED_MPS = 55.6;   // 200 km/h
	private static final double DETOUR_FACTOR = 1.10;      // approach/departure paths

	/**
	 * Passenger ground handling time per boarding and per alighting [s]:
	 * check-in, waiting, safety briefing, boarding. Encoded as travel time on
	 * the vertiport access links, since a teleported mode cannot represent
	 * service times directly. This is what pushes the break-even distance
	 * against car out to roughly 20 km.
	 */
	private static final double GROUND_TIME_S = 300.0;
	private static final double ACCESS_LINK_M = 50.0;

	private static final double BASE_FARE_EUR = 15.0;      // charged per boarding
	private static final double FARE_PER_M_EUR = 0.0035;   // 3.50 EUR/km
	private static final double ASC = -2.0;                // booking friction [utils]

	// ---- run control ----
	/**
	 * 1 pct sample, hardcoded. Injected as command line arguments in main()
	 * rather than loaded directly, so that the framework's own config reader
	 * is used and all custom config groups are registered.
	 */
	private static final String CONFIG_FILE = "input/v1.0/dresden-v1.0-1pct.config.xml";
	private static final String SAMPLE_SIZE = "--1pct";

	private static final int LAST_ITERATION = 0;
	private static final String RUN_ID = "aeroTaxi-P2-1";
	private static final String OUTPUT_DIR = "output/" + RUN_ID;

	public static void main(String[] args) {
		List<String> a = new ArrayList<>(Arrays.asList(args));

		// picocli needs an explicit subcommand
		if (a.isEmpty() || a.get(0).startsWith("-")) {
			a.add(0, "run");
		}
		if (!a.contains("--config")) {
			a.add("--config");
			a.add(CONFIG_FILE);
		}
		if (!a.contains(SAMPLE_SIZE)) {
			a.add(SAMPLE_SIZE);
		}

		log.info("effective arguments: {}", String.join(" ", a));
		MATSimApplication.run(DresdenAeroTaxiScenario.class, a.toArray(new String[0]));
	}

	// =====================================================================
	// 1. CONFIG
	// =====================================================================

	@Override
	protected Config prepareConfig(Config config) {
		super.prepareConfig(config);

		// -- run control
		// NOTE: prepareConfig runs AFTER any --config:... command line
		// overrides, so these lines win over the CLI. For a smoke test, change
		// LAST_ITERATION here rather than passing --config:controler.lastIteration.
		config.controller().setLastIteration(LAST_ITERATION);
		config.controller().setRunId(RUN_ID);
		config.controller().setOutputDirectory(OUTPUT_DIR);
		config.controller().setOverwriteFileSetting(
			OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

		// -- routing: network-routed, but absent from qsim.mainModes, so the
		//    teleportation engine moves the agent along the routed path
		Set<String> networkModes = new LinkedHashSet<>(config.routing().getNetworkModes());
		networkModes.add(MODE);
		config.routing().setNetworkModes(networkModes);

		// access/egress must be on so agents walk to the nearest vertiport.
		// Do not override an existing setting -- that would also change car
		// routing and break comparability with the published base case.
		if (config.routing().getAccessEgressType() == RoutingConfigGroup.AccessEgressType.none) {
			config.routing().setAccessEgressType(
				RoutingConfigGroup.AccessEgressType.accessEgressModeToLink);
		}

		// -- scoring
		ScoringConfigGroup.ModeParams mode = new ScoringConfigGroup.ModeParams(MODE);
		mode.setConstant(ASC);
		mode.setMarginalUtilityOfTraveling(0.0);          // in-vehicle time valued like car
		mode.setMonetaryDistanceRate(-FARE_PER_M_EUR);    // EUR per metre, negative = cost
		config.scoring().addModeParams(mode);

		// stage activity between access walk and flight leg; must not be scored
		if (config.scoring().getActivityParams(INTERACTION) == null) {
			ScoringConfigGroup.ActivityParams act = new ScoringConfigGroup.ActivityParams(INTERACTION);
			act.setScoringThisActivityAtAll(false);
			config.scoring().addActivityParams(act);
		}

		// -- mode choice: without this, no agent ever tries the mode.
		//    aeroTaxi is deliberately NOT chain-based.
		Set<String> choice = new LinkedHashSet<>(Arrays.asList(config.subtourModeChoice().getModes()));
		choice.add(MODE);
		config.subtourModeChoice().setModes(choice.toArray(new String[0]));

		log.info("aeroTaxi registered | {} km/h | {} s ground time | {} EUR + {} EUR/km | ASC {}",
			CRUISE_SPEED_MPS * 3.6, GROUND_TIME_S, BASE_FARE_EUR, FARE_PER_M_EUR * 1000, ASC);
		return config;
	}

	// =====================================================================
	// 2. SCENARIO -- build the air network
	// =====================================================================

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);

		registerVehicleType(scenario);

		Network net = scenario.getNetwork();
		NetworkFactory f = net.getFactory();
		Set<String> only = Set.of(MODE);
		Map<String, Node> airNodes = new LinkedHashMap<>();

		// one ground node and one air node per vertiport, joined by a boarding
		// and an alighting link whose travel time encodes the ground handling
		for (Map.Entry<String, Coord> vp : VERTIPORTS.entrySet()) {
			String id = vp.getKey();
			Coord c = vp.getValue();
			Node ground = f.createNode(Id.createNodeId("vt_" + id + "_g"), c);
			Node sky = f.createNode(Id.createNodeId("vt_" + id + "_a"), c);
			net.addNode(ground);
			net.addNode(sky);
			airNodes.put(id, sky);

			double v = ACCESS_LINK_M / GROUND_TIME_S;
			addLink(net, f, "vt_" + id + "_board", ground, sky, ACCESS_LINK_M, v, only);
			addLink(net, f, "vt_" + id + "_alight", sky, ground, ACCESS_LINK_M, v, only);
		}

		// full mesh of flight links -- point-to-point service, no transfers
		for (Map.Entry<String, Node> from : airNodes.entrySet()) {
			for (Map.Entry<String, Node> to : airNodes.entrySet()) {
				if (from.getKey().equals(to.getKey())) continue;
				double d = CoordUtils.calcEuclideanDistance(
					from.getValue().getCoord(), to.getValue().getCoord()) * DETOUR_FACTOR;
				addLink(net, f, "fly_" + from.getKey() + "_" + to.getKey(),
					from.getValue(), to.getValue(), d, CRUISE_SPEED_MPS, only);
			}
		}

		log.info("air network: {} vertiports, {} links allowing {}", VERTIPORTS.size(),
			net.getLinks().values().stream()
				.filter(l -> l.getAllowedModes().contains(MODE)).count(), MODE);
	}

	/**
	 * qsim.vehiclesSource = modeVehicleTypesFromVehiclesData requires a
	 * VehicleType for every mode that performs network routing -- even a
	 * teleported one that never enters the QSim. Without this, PrepareForSimImpl
	 * aborts with "Could not find requested vehicle type = aeroTaxi".
	 *
	 * The type is a placeholder: pcuEquivalents 0 because it never occupies road
	 * capacity, and NON_HBEFA_VEHICLE so the emissions contrib skips it rather
	 * than failing on missing HBEFA attributes.
	 */
	private void registerVehicleType(Scenario scenario) {
		Vehicles vehicles = scenario.getVehicles();
		Id<VehicleType> id = Id.create(MODE, VehicleType.class);
		if (vehicles.getVehicleTypes().containsKey(id)) {
			return;
		}

		VehicleType vt = VehicleUtils.createVehicleType(id);
		vt.setNetworkMode(MODE);
		vt.setMaximumVelocity(CRUISE_SPEED_MPS);
		vt.setPcuEquivalents(0.0);
		VehicleUtils.setHbefaVehicleCategory(vt.getEngineInformation(), "NON_HBEFA_VEHICLE");
		vehicles.addVehicleType(vt);

		log.info("registered placeholder VehicleType '{}'", MODE);
	}

	private void addLink(Network net, NetworkFactory f, String id, Node from, Node to,
	                     double length, double freespeed, Set<String> modes) {
		Link l = f.createLink(Id.createLinkId(id), from, to);
		l.setLength(length);
		l.setFreespeed(freespeed);
		l.setCapacity(9999);
		l.setNumberOfLanes(1);
		l.setAllowedModes(modes);
		net.addLink(l);
	}

	// =====================================================================
	// 3. CONTROLER -- base fare
	// =====================================================================

	@Override
	protected void prepareControler(Controler controler) {
		super.prepareControler(controler);
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addEventHandlerBinding().to(FareHandler.class);
			}
		});
	}

	/**
	 * Charges the fixed part of the two-part tariff. The distance part is
	 * handled by monetaryDistanceRate in the scoring config.
	 * Negative amount = the agent pays.
	 */
	static final class FareHandler implements PersonDepartureEventHandler {
		private final EventsManager events;

		@Inject
		FareHandler(EventsManager events) {
			this.events = events;
		}

		@Override
		public void handleEvent(PersonDepartureEvent e) {
			if (!MODE.equals(e.getLegMode())) return;
			events.processEvent(new PersonMoneyEvent(e.getTime(), e.getPersonId(),
				-BASE_FARE_EUR, "aeroTaxiFare", "aeroTaxiOperator",
				e.getPersonId().toString()));
		}
	}
}
