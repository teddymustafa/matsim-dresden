package org.matsim.run.scenarios;

import com.google.inject.multibindings.Multibinder;
import org.matsim.analysis.personMoney.PersonMoneyEventsAnalysisModule;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.vsp.pt.fare.PtFareModule;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.dashboards.DresdenDashboardProvider;
import org.matsim.simwrapper.DashboardProvider;
import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;

public class DresdenNoBridges extends DresdenScenario{
	public static void main(String[] args) {
		MATSimApplication.execute(DresdenNoBridges.class, args);
	}

	@Override
	protected Config prepareConfig(Config config){
		super.prepareConfig(config);

		simwrapper = false;

		config.controller().setLastIteration(0);
		config.controller().setOutputDirectory("output-no-bridges");

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);
		// in order: Elbebrucke, Fluegelwegbrucke, Marienbrucke, Augustusbrucke, Carolabrucke, Albertbrucke, Waldschloesschenbrucke
		String[] bridges = {"31059226","318199257","425728245","14448952","-488766980","761288685","-264360404","1031454500","4214231","901959078","-264360396#1","505502627#0","277710971","132572494"};
		for (String bridge : bridges){
			Link link =scenario.getNetwork().getLinks().get(Id.createLinkId(bridge));
			if (link == null){
				System.out.println("Bridge link not found in network. Skipping adjustment.");
				continue;
			}
			link.setFreespeed(0.001);
			link.setCapacity(1);
		}
	}
}
