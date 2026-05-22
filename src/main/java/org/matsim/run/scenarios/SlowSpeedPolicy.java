package org.matsim.run.scenarios;

import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;

public class SlowSpeedPolicy extends DresdenScenario{
	public static void main(String[] args) {
		MATSimApplication.execute(SlowSpeedPolicy.class, args);
	}
	@Override
	protected Config prepareConfig(Config config){
		super.prepareConfig(config);

		simwrapper = false;

		config.controller().setLastIteration(0);
		config.controller().setOutputDirectory("/home/teddymustafa/IdeaProjects/matsim-dresden/output-reduced-speed");

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

	}
}
