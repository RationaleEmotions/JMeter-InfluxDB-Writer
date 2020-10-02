package rocks.nt.apm.jmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterContextService.ThreadCounts;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;

import rocks.nt.apm.jmeter.config.influxdb.InfluxDBConfig;
import rocks.nt.apm.jmeter.config.influxdb.RequestMeasurement;
import rocks.nt.apm.jmeter.config.influxdb.TestStartEndMeasurement;
import rocks.nt.apm.jmeter.config.influxdb.VirtualUsersMeasurement;
import rocks.nt.apm.jmeter.internal.RuntimeBehavior;

/**
 * Backend listener that writes JMeter metrics to influxDB directly.
 * 
 * @author Alexander Wert
 *
 */
public class JMeterInfluxDBBackendListenerClient extends AbstractBackendListenerClient implements Runnable {
	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggingManager.getLoggerForClass();

	/**
	 * Parameter Keys.
	 */
	private static final String KEY_USE_REGEX_FOR_SAMPLER_LIST = "useRegexForSamplerList";
	private static final String KEY_TEST_NAME = "testName";
	private static final String KEY_RUN_ID = "runId";
	private static final String KEY_NODE_NAME = "nodeName";
	private static final String KEY_SAMPLERS_LIST = "samplersList";
	private static final String KEY_RECORD_SUB_SAMPLES = "recordSubSamples";

	/**
	 * Constants.
	 */
	private static final String SEPARATOR = ";";

	/**
	 * Scheduler for periodic metric aggregation.
	 */
	private ScheduledExecutorService scheduler;
	
	private long testStartTime;
	

	/**
	 * Name of the test.
	 */
	private String testName;

	/**
	 * A unique identifier for a single execution (aka 'run') of a load test. In a CI/CD automated
	 * performance test, a Jenkins or Bamboo build id would be a good value for this.
	 */
	private String runId;

	/**
	 * Name of the name
	 */
	private String nodeName;

	/**
	 * Regex if samplers are defined through regular expression.
	 */
	private String regexForSamplerList;

	/**
	 * Set of samplers to record.
	 */
	private Set<String> samplersToFilter;

	/**
	 * InfluxDB configuration.
	 */
	InfluxDBConfig influxDBConfig;

	/**
	 * influxDB client.
	 */
	private InfluxDB influxDB;

	/**
	 * Indicates whether to record Subsamples
	 */
	private boolean recordSubSamples;

	/**
	 * Processes sampler results.
	 */
	public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
		if (RuntimeBehavior.skipRunningListener()) {
			LOGGER.warn(getClass().getName() + " is disabled. Skipping further operations");
			return;
		}
		// Gather all the listeners
		List<SampleResult> allSampleResults = new ArrayList<>();
		for (SampleResult sampleResult : sampleResults) {
			allSampleResults.add(sampleResult);

			if (recordSubSamples) {
				allSampleResults.addAll(Arrays.asList(sampleResult.getSubResults()));
			}
		}
		
		// requestsRaw

		for(SampleResult sampleResult: allSampleResults) {
            getUserMetrics().add(sampleResult);

			if ((null != regexForSamplerList && sampleResult.getSampleLabel().matches(regexForSamplerList)) ||
					samplersToFilter.contains(sampleResult.getSampleLabel())) {
				Point point = Point.measurement(RequestMeasurement.MEASUREMENT_NAME)
						.time(sampleResult.getStartTime(), TimeUnit.MILLISECONDS)
						.tag(RequestMeasurement.Tags.REQUEST_NAME, sampleResult.getSampleLabel())
						.addField(RequestMeasurement.Fields.ERROR_COUNT, sampleResult.getErrorCount())
						.addField(RequestMeasurement.Fields.THREAD_NAME, sampleResult.getThreadName())
						.tag(RequestMeasurement.Tags.RUN_ID, runId)
						.tag(RequestMeasurement.Tags.TEST_NAME, testName)
						.tag("responseCodeTag", sampleResult.getResponseCode())
						.addField(RequestMeasurement.Fields.NODE_NAME, nodeName)
						.addField(RequestMeasurement.Fields.RESPONSE_SIZE, sampleResult.getBytesAsLong())
						.addField(RequestMeasurement.Tags.RESPONSE_CODE, sampleResult.getResponseCode())
						.addField(RequestMeasurement.Fields.RESPONSE_LATENCY, sampleResult.getLatency())
						.addField(RequestMeasurement.Fields.RESPONSE_CONNECT_TIME, sampleResult.getConnectTime())
						.addField(RequestMeasurement.Fields.RESPONSE_TIME, sampleResult.getTime())
						.addField("endTime", sampleResult.getEndTime()).build();

				writeSilently(point);

				long timeToSet = sampleResult.getStartTime() +
						sampleResult.getTime() +
						sampleResult.getConnectTime() +
						sampleResult.getLatency();

				point = Point.measurement("responseRaw")
						.time(timeToSet, TimeUnit.MILLISECONDS)
						.tag(RequestMeasurement.Tags.REQUEST_NAME, sampleResult.getSampleLabel())
						.tag(RequestMeasurement.Tags.RUN_ID, runId)
						.tag(RequestMeasurement.Tags.TEST_NAME, testName)
						.tag("responseCodeTag", sampleResult.getResponseCode())								
						.addField("endTime", sampleResult.getEndTime()).build();
				writeSilently(point);
			}
		}

		for(SampleResult sampleResult: allSampleResults) {
            getUserMetrics().add(sampleResult);

			if ((null != regexForSamplerList && sampleResult.getSampleLabel().matches(regexForSamplerList))
					|| samplersToFilter.contains(sampleResult.getSampleLabel())) {
				Point point = Point.measurement(RequestMeasurement.HISTORY_MEASUTEMENT_NAME)
						.time((sampleResult.getStartTime() - testStartTime) , TimeUnit.MILLISECONDS)
						.tag(RequestMeasurement.Tags.REQUEST_NAME, sampleResult.getSampleLabel())
						.addField(RequestMeasurement.Fields.ERROR_COUNT, sampleResult.getErrorCount())
						.addField(RequestMeasurement.Fields.THREAD_NAME, sampleResult.getThreadName())
						.tag(RequestMeasurement.Tags.RUN_ID, runId)
						.tag(RequestMeasurement.Tags.TEST_NAME, testName)
						.tag("responseCodeTag", sampleResult.getResponseCode())
						.addField(RequestMeasurement.Fields.NODE_NAME, nodeName)
						.addField(RequestMeasurement.Tags.RESPONSE_CODE, sampleResult.getResponseCode())
						.addField(RequestMeasurement.Fields.RESPONSE_SIZE, sampleResult.getBytesAsLong())
						.addField(RequestMeasurement.Fields.RESPONSE_LATENCY, sampleResult.getLatency())
						.addField(RequestMeasurement.Fields.RESPONSE_CONNECT_TIME, sampleResult.getConnectTime())
						.addField(RequestMeasurement.Fields.RESPONSE_TIME, sampleResult.getTime()).build();
				writeSilently(point);
			}
		}
	}

	@Override
	public Arguments getDefaultParameters() {
		Arguments arguments = new Arguments();
		arguments.addArgument(KEY_TEST_NAME, "Test");
		arguments.addArgument(KEY_NODE_NAME, "Test-Node");
		arguments.addArgument(KEY_RUN_ID, "R001");
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_HOST, "localhost");
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_PORT, Integer.toString(InfluxDBConfig.DEFAULT_PORT));
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_USER, "");
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_PASSWORD, "");
		arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_DATABASE, InfluxDBConfig.DEFAULT_DATABASE);
		arguments.addArgument(InfluxDBConfig.KEY_RETENTION_POLICY, InfluxDBConfig.DEFAULT_RETENTION_POLICY);
		arguments.addArgument(KEY_SAMPLERS_LIST, ".*");
		arguments.addArgument(KEY_USE_REGEX_FOR_SAMPLER_LIST, "true");
		arguments.addArgument(KEY_RECORD_SUB_SAMPLES, "true");
		return arguments;
	}

	@Override
	public void setupTest(BackendListenerContext context)  {
		if (RuntimeBehavior.skipRunningListener()) {
			LOGGER.warn(getClass().getName() + " is disabled. Skipping further operations");
			return;
		}
		testStartTime = System.currentTimeMillis();
		testName = context.getParameter(KEY_TEST_NAME, "Test");
		//Will be used to compare performance of R001, R002, etc of 'Test'
		runId = context.getParameter(KEY_RUN_ID,"R001");
		nodeName = context.getParameter(KEY_NODE_NAME, "Test-Node");

		setupInfluxClient(context);
		Point point = Point.measurement(TestStartEndMeasurement.MEASUREMENT_NAME)
				.time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
				.tag(TestStartEndMeasurement.Tags.TYPE, TestStartEndMeasurement.Values.STARTED)
				.tag(TestStartEndMeasurement.Tags.NODE_NAME, nodeName)
				.tag(TestStartEndMeasurement.Tags.TEST_NAME, testName)
				.addField(TestStartEndMeasurement.Fields.PLACEHOLDER, "1")
				.build();

		writeSilently(point);

		parseSamplers(context);
		scheduler = Executors.newScheduledThreadPool(1);

		scheduler.scheduleAtFixedRate(this, 1, 1, TimeUnit.SECONDS);

		// Indicates whether to write sub sample records to the database
		recordSubSamples = Boolean.parseBoolean(context.getParameter(KEY_RECORD_SUB_SAMPLES, "false"));
	}

	@Override
	public void teardownTest(BackendListenerContext context) throws Exception {
		if (RuntimeBehavior.skipRunningListener()) {
			LOGGER.warn(getClass().getName() + " is disabled. Skipping further operations");
			return;
		}

		LOGGER.info("Shutting down influxDB scheduler...");
		scheduler.shutdown();

		addVirtualUsersMetrics(0,0,0,0,JMeterContextService.getThreadCounts().finishedThreads);
		Point point = Point.measurement(TestStartEndMeasurement.MEASUREMENT_NAME)
				.time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
				.tag(TestStartEndMeasurement.Tags.TYPE, TestStartEndMeasurement.Values.FINISHED)
				.tag(TestStartEndMeasurement.Tags.NODE_NAME, nodeName)
				.tag(TestStartEndMeasurement.Tags.RUN_ID, runId)
				.tag(TestStartEndMeasurement.Tags.TEST_NAME, testName)
				.addField(TestStartEndMeasurement.Fields.PLACEHOLDER,"1")
				.build();
		writeSilently(point);

		influxDB.disableBatch();
		try {
			scheduler.awaitTermination(30, TimeUnit.SECONDS);
			LOGGER.info("influxDB scheduler terminated!");
		} catch (InterruptedException e) {
			LOGGER.error("Error waiting for end of scheduler");
		}

		samplersToFilter.clear();
		super.teardownTest(context);
	}

	/**
	 * Periodically writes virtual users metrics to influxDB.
	 */
	public void run() {
		if (RuntimeBehavior.skipRunningListener()) {
			LOGGER.warn(getClass().getName() + " is disabled. Skipping further operations");
			return;
		}
		try {
			ThreadCounts tc = JMeterContextService.getThreadCounts();
			addVirtualUsersMetrics(getUserMetrics().getMinActiveThreads(),
					getUserMetrics().getMeanActiveThreads(),
					getUserMetrics().getMaxActiveThreads(), tc.startedThreads, tc.finishedThreads);
		} catch (Exception e) {
			LOGGER.error("Failed writing to influx", e);
		}
	}

	/**
	 * Setup influxDB client.
	 * 
	 * @param context
	 *            {@link BackendListenerContext}.
	 */
	private void setupInfluxClient(BackendListenerContext context) {
		influxDBConfig = new InfluxDBConfig(context);
		influxDB = InfluxDBFactory.connect(
				influxDBConfig.getInfluxDBURL(),
				influxDBConfig.getInfluxUser(),
				influxDBConfig.getInfluxPassword()
		);
		influxDB.enableBatch(100, 5, TimeUnit.SECONDS);
		createDatabaseIfNotExistent();
	}

	/**
	 * Parses list of samplers.
	 * 
	 * @param context
	 *            {@link BackendListenerContext}.
	 */
	private void parseSamplers(BackendListenerContext context) {
		String samplersList = context.getParameter(KEY_SAMPLERS_LIST, "");
		samplersToFilter = new HashSet<>();
		if (context.getBooleanParameter(KEY_USE_REGEX_FOR_SAMPLER_LIST, false)) {
			regexForSamplerList = samplersList;
		} else {
			regexForSamplerList = null;
			String[] samplers = samplersList.split(SEPARATOR);
			samplersToFilter = new HashSet<>();
			samplersToFilter.addAll(Arrays.asList(samplers));
		}
	}

	/**
	 * Write thread metrics.
	 */
	private void addVirtualUsersMetrics(
			int minActiveThreads,
			int meanActiveThreads,
			int maxActiveThreads,
			int startedThreads,
			int finishedThreads
	) {
		Point point = Point.measurement(VirtualUsersMeasurement.MEASUREMENT_NAME)
				.time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
				.addField(VirtualUsersMeasurement.Fields.MIN_ACTIVE_THREADS, minActiveThreads)
				.addField(VirtualUsersMeasurement.Fields.MAX_ACTIVE_THREADS, maxActiveThreads)
				.addField(VirtualUsersMeasurement.Fields.MEAN_ACTIVE_THREADS, meanActiveThreads)
				.addField(VirtualUsersMeasurement.Fields.STARTED_THREADS, startedThreads)
				.addField(VirtualUsersMeasurement.Fields.FINISHED_THREADS, finishedThreads)
				.tag(VirtualUsersMeasurement.Tags.NODE_NAME, nodeName)
				.tag(VirtualUsersMeasurement.Tags.TEST_NAME, testName)
				.tag(VirtualUsersMeasurement.Tags.RUN_ID, runId)
				.build();
		writeSilently(point);
	}

	private void writeSilently(Point point) {
		try {
			influxDB.write(
					influxDBConfig.getInfluxDatabase(),
					influxDBConfig.getInfluxRetentionPolicy(),
					point);
		} catch (Throwable t) {
			LOGGER.error("Failed writing to influx", t);
		}
	}

	/**
	 * Creates the configured database in influx if it does not exist yet.
	 */
	private void createDatabaseIfNotExistent() {
		List<String> dbNames = influxDB.describeDatabases();
		if (!dbNames.contains(influxDBConfig.getInfluxDatabase())) {
			influxDB.createDatabase(influxDBConfig.getInfluxDatabase());
		}
	}

}
