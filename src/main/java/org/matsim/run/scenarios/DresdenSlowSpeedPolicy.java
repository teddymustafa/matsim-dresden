package org.matsim.run.scenarios;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;

public class DresdenSlowSpeedPolicy extends DresdenScenario {
	public static void main(String[] args) {
		MATSimApplication.execute(DresdenSlowSpeedPolicy.class, args);
	}


	protected Config prepareConfig(Config config) {
		super.prepareConfig(config);

		// no simwrapper output is created. saving runtime. don't work in any scenarios. works here bcs introduced
		simwrapper = false;
		config.controller().setLastIteration(0);
		config.controller().setOutputDirectory("output-slow-speed");


		System.out.println("Here I am.");
		return config;
	}

	protected void prepareScenario(Scenario scenario) {
		super.prepareScenario(scenario);

		scenario.getNetwork().getLinks().get(Id.createLinkId("31059226")).setFreespeed(1.0);


		//Änderungen Hier
	}
}
