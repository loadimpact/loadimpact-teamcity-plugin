package com.loadimpact.teamcity_plugin;

import com.loadimpact.ApiTokenClient;
import com.loadimpact.eval.LoadTestListener;
import com.loadimpact.eval.LoadTestParameters;
import com.loadimpact.util.Parameters;
import com.loadimpact.resource.Test;
import com.loadimpact.resource.TestConfiguration;
import com.loadimpact.resource.testresult.StandardMetricResult;
import com.loadimpact.util.ListUtils;
import com.loadimpact.util.StringUtils;
import jetbrains.buildServer.RunBuildException;
import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.BuildFinishedStatus;
import jetbrains.buildServer.agent.BuildRunnerContext;
import jetbrains.buildServer.agent.artifacts.ArtifactsWatcher;
import org.joda.time.Period;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.loadimpact.resource.testresult.StandardMetricResult.Metrics.BANDWIDTH;
import static com.loadimpact.resource.testresult.StandardMetricResult.Metrics.CLIENTS_ACTIVE;
import static com.loadimpact.resource.testresult.StandardMetricResult.Metrics.REQUESTS_PER_SECOND;
import static com.loadimpact.resource.testresult.StandardMetricResult.Metrics.USER_LOAD_TIME;

/**
 * Runs the load-test build job.
 *
 * @author jens
 */
public class LoadImpactBuildProcess extends FutureBasedBuildProcess {
    private final Debug debug = new Debug(this);
    private final     AgentRunningBuild  build;
    private final     BuildRunnerContext context;
    private final     ArtifactsWatcher   artifactsWatcher;
    private transient String             agentRequestHeaderValue;

    public LoadImpactBuildProcess(AgentRunningBuild build, BuildRunnerContext context, ArtifactsWatcher artifactsWatcher) {
        this.build = build;
        this.context = context;
        this.artifactsWatcher = artifactsWatcher;
    }

    public ApiTokenClient getApiTokenClient(TeamCityLoadTestParameters params) throws RunBuildException {
        String apiToken = params.getApiToken();
        if (StringUtils.isBlank(apiToken)) {
            throw new RunBuildException("Empty API Token");
        }
        final ApiTokenClient client = new ApiTokenClient(apiToken);
        client.setDebug(params.isLogHttp());
        client.setAgentRequestHeaderValue(getAgentRequestHeaderValue(params));
        return client;
    }

    public String getAgentRequestHeaderValue(TeamCityLoadTestParameters params) {
        if (agentRequestHeaderValue == null) {
            String pluginVersion = getMavenPomData().getProperty("version", "0.0.0");
            String teamCityVersion = params.getTeamCityVersion();
            agentRequestHeaderValue = String.format("LoadImpactTeamCityPlugin/%s TeamCity/%s", pluginVersion, teamCityVersion);
            debug.print("Agent REQ HDR: %s", agentRequestHeaderValue);
        }
        return agentRequestHeaderValue;
    }

    public Properties getMavenPomData() {
        Properties  p       = new Properties();
        String      pomFile = "/META-INF/maven/com.loadimpact/LoadImpact-TeamCity-plugin-agent/pom.properties";
        InputStream is      = getClass().getResourceAsStream(pomFile);
        if (is != null) {
            try {
                p.load(is);
            } catch (IOException ignore) {
            }
        }
        return p;
    }


    public BuildFinishedStatus call() throws Exception {
        final TeamCityLoadTestLogger logger = new TeamCityLoadTestLogger(build.getBuildLogger());
        logger.started("Load Test");

        TeamCityLoadTestParameters params = new TeamCityLoadTestParameters(new Parameters(context.getRunnerParameters()));
        Debug.setEnabled(params.isLogDebug());
        debug.print(params.toString());

        final ApiTokenClient           client           = getApiTokenClient(params);
        TeamCityLoadTestResultListener resultListener   = new TeamCityLoadTestResultListener(logger, build);
        LoadTestListener               loadTestListener = new LoadTestListener(params, logger, resultListener);

        Test test;
        try {
            logger.message("Fetching the test-configuration");
            TestConfiguration testConfiguration = client.getTestConfiguration(params.getTestConfigurationId());
            loadTestListener.onSetup(testConfiguration, client);

            logger.message("Launching the load test");
            int testId = client.startTest(testConfiguration.id);

            test = client.monitorTest(testId, params.getPollInterval(), loadTestListener);
        } catch (Exception x) {
            logger.failure(x.getMessage());
            return BuildFinishedStatus.FINISHED_FAILED;
        }

        Properties results = new Properties();
        if (test == null) {
            logger.failure("Load test failed");
            results.setProperty("status", "error");
            results.setProperty("reason", resultListener.getReason());
        } else {
            if (resultListener.isNonSuccessful()) {
                logger.failure(resultListener.getReason());
                logger.message("Collecting load-test failure data");
                results.setProperty("status", "failure");
                results.setProperty("reason", resultListener.getReason());
            } else {
                logger.message("Collecting load-test success data");
                results.setProperty("status", "success");
            }
            results = populateResults(results, test, client);
        }
        File file = storeResultProperties(results);
        debug.print("--- Load Test Results ---%nFile=%s%n%s", file, toString(results));

        logger.finished(null);
        return resultListener.getStatus();
    }

    Properties populateResults(Properties results, Test tst, ApiTokenClient client) {
        results.setProperty("testId", Integer.toString(tst.id));
        results.setProperty("testName", tst.title);
        results.setProperty("targetUrl", toString(tst.url));
        results.setProperty("resultUrl", toString(tst.publicUrl, "/embed"));
        results.setProperty("elapsedTime", computeElapsedTime(tst));
        results.setProperty("responseTime", computeResponseTime(tst, client));
        results.setProperty("clientsCount", computeClientsCount(tst, client));
        results.setProperty("requestsCount", computeRequestsCount(tst, client));
        results.setProperty("bandwidth", computeBandwidth(tst, client));

        return results;
    }

    private String toString(final Object obj, final String suffix) {
        String prefix = toString(obj);
        if (prefix.isEmpty()) return "";
        return prefix + suffix;
    }

    private String toString(final Object obj) {
        if (obj == null) return "";
        String result = obj.toString();
        if (result == null) return "";
        return result;
    }

    String computeElapsedTime(Test tst) {
        return timeFmt().print(new Period(tst.started.getTime(), tst.ended.getTime()));
    }

    @SuppressWarnings("unchecked")
    String computeResponseTime(Test tst, ApiTokenClient client) {
        List<StandardMetricResult> results = (List<StandardMetricResult>) client.getStandardMetricResults(tst.id, USER_LOAD_TIME, null, null);
        List<Double> values = ListUtils.map(results, new ListUtils.MapClosure<StandardMetricResult, Double>() {
            @Override
            public Double eval(StandardMetricResult r) {
                return r.value.doubleValue();
            }
        });
        return timeFmt().print(new Period((long) ListUtils.average(values)));
    }

    @SuppressWarnings("unchecked")
    String computeClientsCount(Test tst, ApiTokenClient client) {
        List<StandardMetricResult> results = (List<StandardMetricResult>) client.getStandardMetricResults(tst.id, CLIENTS_ACTIVE, null, null);
        List<Integer> values = ListUtils.map(results, new ListUtils.MapClosure<StandardMetricResult, Integer>() {
            @Override
            public Integer eval(StandardMetricResult r) {
                return r.value.intValue();
            }
        });
        return String.valueOf(Collections.max(values));
    }

    @SuppressWarnings("unchecked")
    String computeRequestsCount(Test tst, ApiTokenClient client) {
        List<StandardMetricResult> results = (List<StandardMetricResult>) client.getStandardMetricResults(tst.id, REQUESTS_PER_SECOND, null, null);
        List<Double> values = ListUtils.map(results, new ListUtils.MapClosure<StandardMetricResult, Double>() {
            @Override
            public Double eval(StandardMetricResult r) {
                return r.value.doubleValue();
            }
        });
        int avg = (int) ListUtils.average(values);
        int max = Collections.max(values).intValue();
        return String.format("%d (max %d) requests per second", avg, max);
    }

    @SuppressWarnings("unchecked")
    String computeBandwidth(Test tst, ApiTokenClient client) {
        List<StandardMetricResult> results = (List<StandardMetricResult>) client.getStandardMetricResults(tst.id, BANDWIDTH, null, null);
        List<Double> values = ListUtils.map(results, new ListUtils.MapClosure<StandardMetricResult, Double>() {
            @Override
            public Double eval(StandardMetricResult r) {
                return r.value.doubleValue();
            }
        });
        double avg = ListUtils.average(values) / 1E6;
        double max = Collections.max(values).intValue() / 1E6;
        return String.format("%.3f (max %.3f) MBits per second", avg, max);
    }


    File storeResultProperties(Properties p) throws IOException {
        File       buildDir    = build.getBuildTempDirectory();
        File       resultsFile = new File(buildDir, Constants.resultsFile);
        FileWriter fileWriter  = new FileWriter(resultsFile);
        p.store(fileWriter, "");
        fileWriter.close();
        artifactsWatcher.addNewArtifactsPath(resultsFile.getAbsolutePath());
        return resultsFile;
    }

    PeriodFormatter timeFmt() {
        return new PeriodFormatterBuilder()
                .minimumPrintedDigits(0)
                .printZeroNever()
                .appendHours()
                .appendSeparator("h ")
                .appendMinutes()
                .appendSeparator("m ")
                .appendSeconds()
                .appendSuffix("s")
                .toFormatter();
    }

    String toString(Properties properties) {
        StringBuilder buf = new StringBuilder(10000);
        for (Map.Entry<Object, Object> e : properties.entrySet()) {
            buf.append(String.format("  %s: %s%n", e.getKey(), e.getValue()));
        }
        return buf.toString();
    }

}
