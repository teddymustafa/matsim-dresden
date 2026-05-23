package org.matsim.run.scenarios;

import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;

public class DresdenBaseCase extends DresdenScenario {
	public static void main(String[] args) {
		MATSimApplication.execute(DresdenBaseCase.class, args);
	}

	@Override
	protected Config prepareConfig(Config config){
		super.prepareConfig(config);

		config.controller().setLastIteration(100);
		config.controller().setOutputDirectory("output-base-case-100");

		return config;
	}
}
