package cc.blynk.server.workers;

import cc.blynk.server.core.dao.ReportingDao;
import cc.blynk.server.core.model.widgets.outputs.graph.GraphGranularityType;
import cc.blynk.server.core.reporting.average.AggregationKey;
import cc.blynk.server.core.reporting.average.AggregationValue;
import cc.blynk.server.db.DBManager;
import cc.blynk.utils.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static cc.blynk.server.core.dao.ReportingDao.generateFilename;

/**
 * Worker that runs once a minute. During run - stores all aggregated reporting data
 * to disk. Also sends all data in batches to RDBMS in case DBManager was initialized.
 *
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 10.08.15.
 */
public class ReportingWorker implements Runnable {

    private static final Logger log = LogManager.getLogger(ReportingWorker.class);

    private final ReportingDao reportingDao;
    private final String reportingPath;
    private final DBManager dbManager;

    public ReportingWorker(ReportingDao reportingDao, String reportingPath, DBManager dbManager) {
        this.reportingDao = reportingDao;
        this.reportingPath = reportingPath;
        this.dbManager = dbManager;
    }

    @Override
    public void run() {
        try {
            Map<AggregationKey, AggregationValue> removedKeysMinute =
                    process(reportingDao.averageAggregator.getMinute(), GraphGranularityType.MINUTE);
            Map<AggregationKey, AggregationValue> removedKeysHour =
                    process(reportingDao.averageAggregator.getHourly(), GraphGranularityType.HOURLY);
            Map<AggregationKey, AggregationValue> removedKeysDay =
                    process(reportingDao.averageAggregator.getDaily(), GraphGranularityType.DAILY);

            dbManager.insertReporting(removedKeysMinute, GraphGranularityType.MINUTE);
            dbManager.insertReporting(removedKeysHour, GraphGranularityType.HOURLY);
            dbManager.insertReporting(removedKeysDay, GraphGranularityType.DAILY);

            dbManager.insertReportingRaw(reportingDao.rawDataProcessor.rawStorage);

            dbManager.cleanOldReportingRecords(Instant.now());
        } catch (Exception e) {
            log.error("Error during reporting job.", e);
        }
    }

    /**
     * Iterates over all reporting entries that were created during last minute.
     * And stores all entries one by one to disk.
     *
     * @param map - reporting entires that were created during last minute.
     * @param type - type of reporting. Could be minute, hourly, daily.
     * @return - returns list of reporting entries that were successfully flushed to disk.
     */
    private Map<AggregationKey, AggregationValue>  process(Map<AggregationKey, AggregationValue> map,
                                                           GraphGranularityType type) {
        if (map.size() == 0) {
            return Collections.emptyMap();
        }

        Set<AggregationKey> aggregationKeySet = map.keySet();
        AggregationKey[] keys = aggregationKeySet.toArray(new AggregationKey[aggregationKeySet.size()]);
        Arrays.sort(keys, AggregationKey.AGGREGATION_KEY_COMPARATOR);

        Map<AggregationKey, AggregationValue> removedKeys = new HashMap<>();

        long nowTruncatedToPeriod = System.currentTimeMillis() / type.period;
        for (AggregationKey keyToRemove : keys) {
            //if prev hour
            if (keyToRemove.isOutdated(nowTruncatedToPeriod)) {
                AggregationValue value = map.get(keyToRemove);

                try {
                    Path userReportFolder = Paths.get(reportingPath,
                            FileUtils.getUserReportingDir(keyToRemove.getEmail(), keyToRemove.getAppName()));
                    if (Files.notExists(userReportFolder)) {
                        Files.createDirectories(userReportFolder);
                    }

                    String fileName = generateFilename(keyToRemove.getDashId(),
                            keyToRemove.getDeviceId(), keyToRemove.getPinType(), keyToRemove.getPin(), type);
                    Path filePath = Paths.get(userReportFolder.toString(), fileName);

                    FileUtils.write(filePath, value.calcAverage(), keyToRemove.getTs(type));

                    removedKeys.put(keyToRemove, value);
                } catch (Exception ioe) {
                    log.error("Error writing reporting file. Reason : {}", ioe.getMessage());
                } finally {
                    map.remove(keyToRemove);
                }
            }
        }

        return removedKeys;
    }

}
