package org.matsim.run.scenarios;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.Collections;

public class DresdenNoBridgesAtAll extends DresdenScenario{
	public static void main(String[] args) {
		MATSimApplication.execute(DresdenNoBridgesAtAll.class, args);
	}
	@Override
	protected Config prepareConfig(Config config){
		super.prepareConfig(config);

		simwrapper = false;

		config.controller().setLastIteration(100);
		config.controller().setOutputDirectory("output-no-bridges-at-all-100-final");
		config.routing().setNetworkRouteConsistencyCheck(RoutingConfigGroup.NetworkRouteConsistencyCheck.disable);

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);
		// in order: Elbebrucke, Fluegelwegbrucke, Marienbrucke, Augustusbrucke, Carolabrucke, Albertbrucke, Waldschloesschenbrucke, Loeschwitzbrucke
		String[] bridges = {"31059226","318199257","425728245","14448952","-488766980","761288685","-264360404","1031454500","4214231","901959078","-264360396#1","505502627#0","277710971","132572494","-30129851","30129851"};
		for (String bridge : bridges){
			Link link =scenario.getNetwork().getLinks().get(Id.createLinkId(bridge));
			if (link == null){
				System.out.println("Bridge link not found in network. Skipping adjustment.");
				continue;
			}
			link.setAllowedModes(Collections.emptySet());
		}
		ScenarioUtils.cleanScenario(scenario);
	}
}
