package org.matsim.run;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import com.google.common.collect.Sets;
import com.google.inject.Provides;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.analysis.ModeAnalysisWithHomeLocationFilter;
import org.matsim.analysis.ModeChoiceCoverageControlerListener;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.AnalysisSummary;
import org.matsim.application.analysis.CheckPopulation;
import org.matsim.application.analysis.LinkStats;
import org.matsim.application.analysis.emissions.AirPollutionByVehicleCategory;
import org.matsim.application.analysis.emissions.AirPollutionSpatialAggregation;
import org.matsim.application.options.SampleOptions;
import org.matsim.application.prepare.freight.ExtractRelevantFreightTrips;
import org.matsim.application.prepare.population.*;
import org.matsim.contrib.signals.otfvis.OTFVisWithSignalsLiveModule;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.*;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup.ActivityParams;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.mobsim.qsim.qnetsimengine.ConfigurableQNetworkFactory;
import org.matsim.core.mobsim.qsim.qnetsimengine.QLanesNetworkFactory;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetworkFactory;
import org.matsim.lanes.Lane;
import org.matsim.lanes.LanesToLinkAssignment;
import org.matsim.prepare.*;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@CommandLine.Command(header = ":: Open Düsseldorf Scenario ::", version = RunDuesseldorfScenario.VERSION)
@MATSimApplication.Prepare({
		CreateNetwork.class, CreateTransitSchedule.class, CreateCityCounts.class, CleanPopulation.class,
		ExtractEvents.class, CreateBAStCounts.class, TrajectoryToPlans.class, ExtractRelevantFreightTrips.class,
		GenerateShortDistanceTrips.class, MergePopulations.class, DownSamplePopulation.class, ResolveGridCoordinates.class
})
@MATSimApplication.Analysis({
		AnalysisSummary.class, CheckPopulation.class, ModeAnalysisWithHomeLocationFilter.class,
		AirPollutionByVehicleCategory.class, AirPollutionSpatialAggregation.class, LinkStats.class
})
public class RunDuesseldorfScenario extends MATSimApplication {

	private static final Logger log = LogManager.getLogger(RunDuesseldorfScenario.class);

	/**
	 * Current version identifier.
	 */
	public static final String VERSION = "v1.6";

	/**
	 * Default coordinate system of the scenario.
	 */
	public static final String COORDINATE_SYSTEM = "EPSG:25832";

	/**
	 * 6.00° - 7.56°
	 */
	public static final double[] X_EXTENT = new double[]{290_000.00, 400_000.0};
	/**
	 * 50.60 - 51.65°
	 */
	public static final double[] Y_EXTENT = new double[]{5_610_000.00, 5_722_000.00};

	@CommandLine.Option(names = "--otfvis", defaultValue = "false", description = "Enable OTFVis live view")
	private boolean otfvis;

	@CommandLine.Mixin
	private SampleOptions sample = new SampleOptions(1, 10, 25);

	@CommandLine.Option(names = {"--dc"}, defaultValue = "1", description = "Correct demand by downscaling links")
	private double demandCorrection;

	@CommandLine.Option(names = {"--no-lanes"}, defaultValue = "false", description = "Deactivate the use of lane information")
	private boolean noLanes;

	@CommandLine.Option(names = {"--lane-capacity"}, description = "CSV file with lane capacities", required = false)
	private Path laneCapacity;

	@CommandLine.Option(names = {"--capacity-factor"}, defaultValue = "1", description = "Scale lane capacity by this factor.")
	private double capacityFactor;

	@CommandLine.Option(names = {"--no-capacity-reduction"}, defaultValue = "false", description = "Disable reduction of flow capacity for taking turns")
	private boolean noCapacityReduction;

	@CommandLine.Option(names = {"--free-flow"}, defaultValue = "1", description = "Scale up free flow speed of slow links.")
	private double freeFlowFactor;

	@CommandLine.Option(names = "--no-mc", defaultValue = "false", description = "Disable mode choice as replanning strategy.")
	private boolean noModeChoice;

	public RunDuesseldorfScenario() {
		super("scenarios/input/duesseldorf-v1.0-1pct.config.xml");
	}

	public RunDuesseldorfScenario(Config config) {
		super(config);
	}

	public static void main(String[] args) {
		MATSimApplication.run(RunDuesseldorfScenario.class, args);
	}

	@Override
	protected Config prepareConfig(Config config) {

		// addDefaultActivityParams(config);

		for (long ii = 600; ii <= 97200; ii += 600) {

			for (String act : List.of("home", "restaurant", "other", "visit", "errands", "educ_higher",
					"educ_secondary")) {
				config.planCalcScore()
						.addActivityParams(new ActivityParams(act + "_" + ii + ".0").setTypicalDuration(ii));
			}

			config.planCalcScore().addActivityParams(new ActivityParams("work_" + ii + ".0").setTypicalDuration(ii)
					.setOpeningTime(6. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new ActivityParams("business_" + ii + ".0").setTypicalDuration(ii)
					.setOpeningTime(6. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new ActivityParams("leisure_" + ii + ".0").setTypicalDuration(ii)
					.setOpeningTime(9. * 3600.).setClosingTime(27. * 3600.));
			config.planCalcScore().addActivityParams(new ActivityParams("shopping_" + ii + ".0").setTypicalDuration(ii)
					.setOpeningTime(8. * 3600.).setClosingTime(20. * 3600.));
		}

		// Config changes for larger samples
		if (sample.getSize() != 1) {

			config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));
			config.controler().setRunId(sample.adjustName(config.controler().getRunId()));
			config.controler().setOutputDirectory(sample.adjustName(config.controler().getOutputDirectory()));

			// Further reduction of flow capacity because of difference in the absolute, number of trips by 1.78x
			config.qsim().setFlowCapFactor(sample.getSize() / (100.0 * demandCorrection));
			config.qsim().setStorageCapFactor(sample.getSize() / (100.0 * demandCorrection));
		}

		if (demandCorrection != 1.0)
			addRunOption(config, "dc", demandCorrection);

		if (noLanes) {

			config.qsim().setUseLanes(false);
			config.controler().setLinkToLinkRoutingEnabled(false);
			config.network().setLaneDefinitionsFile(null);
			config.travelTimeCalculator().setCalculateLinkToLinkTravelTimes(false);

			addRunOption(config, "no-lanes");

			config.controler().setRoutingAlgorithmType(ControlerConfigGroup.RoutingAlgorithmType.SpeedyALT);

		}

		if (capacityFactor != 1.0)
			addRunOption(config, "cap", capacityFactor);

		if (freeFlowFactor != 1)
			addRunOption(config, "ff", freeFlowFactor);

		if (noModeChoice) {

			List<StrategyConfigGroup.StrategySettings> strategies = config.strategy().getStrategySettings().stream()
					.filter(s -> !s.getStrategyName().equals("SubtourModeChoice")).collect(Collectors.toList());

			config.strategy().clearStrategySettings();
			strategies.forEach(s -> config.strategy().addStrategySettings(s));

			addRunOption(config, "noMc");
		}

		if (noCapacityReduction)
			addRunOption(config, "no-cap-red");

		// config.planCalcScore().addActivityParams(new
		// ActivityParams("freight").setTypicalDuration(12. * 3600.));
		config.planCalcScore().addActivityParams(new ActivityParams("car interaction").setTypicalDuration(60));
		config.planCalcScore().addActivityParams(new ActivityParams("other").setTypicalDuration(600 * 3));

		config.planCalcScore().addActivityParams(new ActivityParams("freight_start").setTypicalDuration(60 * 15));
		config.planCalcScore().addActivityParams(new ActivityParams("freight_end").setTypicalDuration(60 * 15));

		// vsp defaults
		config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.info);
		config.plansCalcRoute().setAccessEgressType(PlansCalcRouteConfigGroup.AccessEgressType.accessEgressModeToLink);
		config.qsim().setUsingTravelTimeCheckInTeleportation(true);
//		config.qsim().setTrafficDynamics(QSimConfigGroup.TrafficDynamics.kinematicWaves);

		config.plans().setHandlingOfPlansWithoutRoutingMode(
				PlansConfigGroup.HandlingOfPlansWithoutRoutingMode.useMainModeIdentifier);

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

		if (laneCapacity != null) {

			Object2DoubleMap<Triple<Id<Link>, Id<Link>, Id<Lane>>> map = CreateNetwork
					.readLaneCapacities(laneCapacity);

			log.info("Overwrite capacities from {}, containing {} lanes", laneCapacity, map.size());

			int n = CreateNetwork.setLinkCapacities(scenario.getNetwork(), map);
			int n2 = CreateNetwork.setLaneCapacities(scenario.getLanes(), map);

			log.info("Unmatched links: {}, lanes: {}", n, n2);
		}

		if (!noLanes) {

			// scale lane capacities
			for (LanesToLinkAssignment l2l : scenario.getLanes().getLanesToLinkAssignments().values()) {
				for (Lane lane : l2l.getLanes().values()) {
					lane.setCapacityVehiclesPerHour(lane.getCapacityVehiclesPerHour() * capacityFactor);
				}
			}
		}

		// scale free flow speed
		for (Link link : scenario.getNetwork().getLinks().values()) {
			if (link.getFreespeed() < 25.5 / 3.6) {
				link.setFreespeed(link.getFreespeed() * freeFlowFactor);
			}

			// might be null, so avoid unboxing
			if (link.getAttributes().getAttribute("junction") == Boolean.TRUE
					|| "traffic_light".equals(link.getToNode().getAttributes().getAttribute("type")))
				link.setCapacity(link.getCapacity() * capacityFactor);

			Set<String> modes = link.getAllowedModes();

			// allow freight traffic together with cars
			if (modes.contains("car")) {
				HashSet<String> newModes = Sets.newHashSet(modes);
				newModes.add("freight");

				link.setAllowedModes(newModes);
			}
		}

	}

	@Override
	protected void prepareControler(Controler controler) {

		if (otfvis)
			controler.addOverridingModule(new OTFVisWithSignalsLiveModule());

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				install(new SwissRailRaptorModule());
				addControlerListenerBinding().to(ModeChoiceCoverageControlerListener.class);
			}
		});

		controler.addOverridingQSimModule(new AbstractQSimModule() {
			@Override
			protected void configureQSim() {
			}

			@Provides
			QNetworkFactory provideQNetworkFactory(EventsManager eventsManager, Scenario scenario) {
				ConfigurableQNetworkFactory factory = new ConfigurableQNetworkFactory(eventsManager, scenario);

				TurnDependentFlowEfficiencyCalculator fe = null;
				if (!noCapacityReduction) {
					fe = new TurnDependentFlowEfficiencyCalculator(scenario);
					factory.setFlowEfficiencyCalculator(fe);
				}

				if (noLanes)
					return factory;
				else {
					QLanesNetworkFactory wrapper = new QLanesNetworkFactory(eventsManager, scenario);
					wrapper.setDelegate(factory);
					if (fe != null)
						wrapper.setFlowEfficiencyCalculator(fe);

					return wrapper;
				}
			}
		});

	}

	/**
	 * Option group for desired sample size.
	 */
	static final class Sample {

		@CommandLine.Option(names = {"--25pct",
				"--prod"}, defaultValue = "false", description = "Use the 25pct scenario")
		private boolean p25;

		@CommandLine.Option(names = {"--10pct"}, defaultValue = "false", description = "Use the 10pct sample")
		private boolean p10;

		@CommandLine.Option(names = {"--1pct"}, defaultValue = "true", description = "Use the 1pct sample")
		private boolean p1 = true;

		/**
		 * Get configured sample size.
		 */
		int getSize() {
			if (p25)
				return 25;
			if (p10)
				return 10;
			if (p1)
				return 1;
			throw new IllegalStateException("No sample size defined");
		}

	}
}
