package org.matsim.run;

import org.junit.Rule;
import org.junit.Test;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.testcases.MatsimTestUtils;

public class RunDuesseldorfIntegrationTest {
	@Rule
	public MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	public final void runOneAgentTest() {

		Config config = ConfigUtils.loadConfig("scenarios/input/test.config.xml");
		config.plans().setInputFile("test-plans.xml");
		config.controler().setLastIteration(1);
		config.strategy().setFractionOfIterationsToDisableInnovation(1);
		config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controler().setOutputDirectory(utils.getOutputDirectory());

		MATSimApplication.run(RunDuesseldorfScenario.class, config, new String[]{
				"--no-lanes"
		});

	}


	@Test
	public final void runNoLaneTest() {

		Config config = ConfigUtils.loadConfig("scenarios/input/test.config.xml");
		config.controler().setLastIteration(1);
		config.strategy().setFractionOfIterationsToDisableInnovation(1);
		config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controler().setOutputDirectory(utils.getOutputDirectory());

		MATSimApplication.run(RunDuesseldorfScenario.class, config, new String[]{
				"--no-lanes"
		});
	}


	@Test
	public final void runWithLaneTest() {

		Config config = ConfigUtils.loadConfig("scenarios/input/test.config.xml");
		config.controler().setLastIteration(1);
		config.strategy().setFractionOfIterationsToDisableInnovation(1);
		config.controler().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controler().setOutputDirectory(utils.getOutputDirectory());

		// default options
		MATSimApplication.run(RunDuesseldorfScenario.class, config, new String[]{});

	}

}