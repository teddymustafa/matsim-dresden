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
 * Run (the 1 pct config file is hardcoded in main; iterations, run id
 * and output directory are set in prepareConfig):
 *   IntelliJ program arguments:  run
 *   Command line:  java -Xmx11g -cp matsim-dresden.jar \
 *                       org.matsim.run.scenarios.DresdenAeroTaxiScenario run
 *
 * TODO before the first run: copy the @MATSimApplication.Prepare and
 * @MATSimApplication.Analysis annotation blocks from DresdenScenario onto this
 * class. Java does not inherit annotations, so without them the scenario's
 * preparation steps and the automatic SimWrapper dashboards are lost.
 *
 * -------------------------------------------------------------------------
 * LIMITATIONS (accepted for this study, given time constraints)
 * -------------------------------------------------------------------------
 *  1. Full mesh, no minimum flight distance: inner-city vertiports (HBF,
 *     NST, ALT, TUD) are connected by flights of a few hundred metres that
 *     agents may use, pulling the average aeroTaxi trip below any realistic
 *     break-even distance.
 *  2. Vertiports attach to the nearest street node by straight-line
 *     distance only. For rural sites (MEI, PIR) this node may be far away
 *     and not genuinely walk-accessible; access distance is not validated.
 *  3. Ground handling (2 x GROUND_TIME_S) is modelled as travel time on the
 *     board/alight links. It is therefore valued only at the opportunity
 *     cost of foregone activity time (~performing utility), with no extra
 *     mode-specific time penalty.
 *  4. Fare and ASC are illustrative, not calibrated.
 *  5. Teleported mode: unlimited capacity, no vertiport queues, no fleet
 *     size constraint, and no interaction with road congestion.
 *  6. Base @MATSimApplication.Prepare / @Analysis annotations are not
 *     carried over, so some SimWrapper dashboards may be reduced.
 * -------------------------------------------------------------------------
 *
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
	 * service times directly. This time is valued only at the opportunity cost
	 * of foregone activity time (~performing utility, inherited from
	 * DresdenScenario); there is no extra mode-specific time penalty.
	 */
	private static final double GROUND_TIME_S = 120.0;
	private static final double ACCESS_LINK_M = 50.0;

	private static final double BASE_FARE_EUR = 20.0;     // booking/handling fee, charged per boarding
	private static final double FARE_PER_M_EUR = 0.0020;   // 2.00 EUR/km (near-term eVTOL, > ground taxi)
	// Lumped intangible penalty [utils] for a novel air mode -- unfamiliarity,
	// distrust, perceived flight risk, comfort. Net negative; captures only the
	// non-time, non-money effects (physical ground time is on the links, so this
	// does not double-count it). Assumed, not calibrated.
	private static final double ASC = -2.0;

	// ---- run control ----
	/**
	 * 1 pct sample, hardcoded. Injected as command line arguments in main()
	 * rather than loaded directly, so that the framework's own config reader
	 * is used and all custom config groups are registered.
	 */
	private static final String CONFIG_FILE = "input/v1.0/dresden-v1.0-1pct.config.xml";
	private static final int LAST_ITERATION = 5;
	private static final String RUN_ID = "aeroTaxi-P2-fareverify";
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
		config.controller().setWriteEventsInterval(1);   // TEMP: fare-verification run only

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
		// In-vehicle and ground time are valued like car time: no extra
		// mode-specific penalty, so travel time costs only the opportunity cost
		// of foregone activity time (~performing utility). A deliberate
		// simplification -- see the LIMITATIONS block on the class.
		mode.setMarginalUtilityOfTraveling(0.0);
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

		// Use the original street network as the ground side of each vertiport.
		// This removes the isolated ground nodes from the previous version and
		// lets access/egress walks end at a real street-network node.
		List<Node> streetNodes = new ArrayList<>(net.getNodes().values());

		for (Map.Entry<String, Coord> vp : VERTIPORTS.entrySet()) {
			String id = vp.getKey();
			Coord c = vp.getValue();
			Node street = findNearestNode(streetNodes, c);
			Node sky = f.createNode(Id.createNodeId("vt_" + id + "_a"), c);
			net.addNode(sky);
			airNodes.put(id, sky);

			double v = ACCESS_LINK_M / GROUND_TIME_S;
			addLink(net, f, "vt_" + id + "_board", street, sky, ACCESS_LINK_M, v, only);
			addLink(net, f, "vt_" + id + "_alight", sky, street, ACCESS_LINK_M, v, only);

			log.info("vertiport {} attached to street node {} (offset {} m)",
				id, street.getId(), Math.round(CoordUtils.calcEuclideanDistance(c, street.getCoord())));
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

	private static Node findNearestNode(Collection<Node> nodes, Coord coord) {
		Node nearest = null;
		double bestDistance = Double.POSITIVE_INFINITY;

		for (Node node : nodes) {
			double distance = CoordUtils.calcEuclideanDistance(coord, node.getCoord());
			if (distance < bestDistance) {
				bestDistance = distance;
				nearest = node;
			}
		}

		if (nearest == null) {
			throw new IllegalStateException("Cannot attach vertiport: street network has no nodes");
		}
		return nearest;
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
