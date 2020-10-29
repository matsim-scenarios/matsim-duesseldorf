package org.matsim.prepare;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.counts.Count;
import org.matsim.counts.Counts;
import org.matsim.counts.CountsWriter;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@CommandLine.Command(
        name = "createCityCounts",
        description = "Aggregate and convert counts from inner city"
)
public class CreateCityCounts implements Callable<Integer> {

    private static final Logger log = LogManager.getLogger(CreateCityCounts.class);

    // is the declaration later possible?
    Set<String> allStations = new HashSet<>();
    Counts<Link> finalCounts = new Counts<>();

    @CommandLine.Option(names = {"--input"}, description = "Input folder with zip files",
            defaultValue = "../../shared-svn/komodnext/data/counts")
    private Path input;

    @CommandLine.Option(names = {"--output"}, description = "Output counts.xml.gz",
            defaultValue = ".../.../public-svn/matsim/scenarios/countries/de/duesseldorf/duesseldorf-v1.0/matsim-input-files/output.xml.gz")
    private String output;

    public static void main(String[] args) throws IOException {

        System.exit(new CommandLine(new CreateCityCounts()).execute(args));
    }

    @Override
    public Integer call() throws Exception {

        // map of month to counts
        Map<String, Counts<Link>> collect = Files.list(input)
                .filter(f -> f.getFileName().toString().endsWith(".zip"))
                .collect(Collectors.toList()) // need to collect first
                .parallelStream()
                .map(this::readCounts)
                .collect(Collectors.toMap(Counts::getName, Function.identity()));

        for (var counts : collect.entrySet()) {
            log.info("**********************************************************************************************************************************");
            log.info("* Month: " + counts.getKey());

            for (Count<Link> value : counts.getValue().getCounts().values()) {
                log.info("LinkId : {}", value.getId());
                log.info("* Counts per hour: {}", value.getVolumes());
            }

            log.info("**********************************************************************************************************************************");
        }

        //TODO: Delete empty counts

        finalCounts = aggregateCounts(collect);
        finalCounts.setYear(2019);
        new CountsWriter(finalCounts).write(output);

        return 0;
    }

    /**
     * Read one month of count data from for all sensors for zip file.
     */
    private Counts<Link> readCounts(Path zip) {

        Counts<Link> counts = new Counts<>();
        counts.setYear(2019);
        String monthNumber = zip.getFileName().toString().split("-")[0];
        counts.setName(monthNumber);

        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {

            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory())
                    continue;

                String stationId = entry.getName().split("_")[2];
                stationId = stationId.substring(0, stationId.length() - 4);

                allStations.add(stationId);

                // TODO: lookup map matched link id
                readCsvCounts(in, counts.createAndAddCount(Id.createLinkId(stationId), stationId));

                log.info("Finished reading {}", entry.getName());
            }
        } catch (IOException e) {
            log.error("Could not read zip file {}", zip, e);
        }

        return counts;
    }

    /**
     * Read counts from CSV data for one station and aggregate whole month into one count object.
     *
     * @param count count object of the link that must be populated with count data
     */
    private void readCsvCounts(ZipInputStream in, Count<Link> count) throws IOException {

        Map<Integer, List<Double>> tempCountSum = new HashMap<>();
        Map<String, Count<Link>> result = new HashMap<>();
        List<String> holidays2019 = Arrays.asList("01.01.2019", "19.04.2019", "22.04.2019", "01.05.2019",
                "30.05.2019", "10.06.2019", "20.06.2019", "03.10.2019", "01.11.2019", "25.12.2019",
                "26.12.2019");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        List<Integer> weekendDaysList = Arrays.asList(1, 5, 6, 7);
        Integer[] hourCountsTmp = new Integer[24];
        Double countMean;
        double sum = 0D;

        InputStreamReader reader = new InputStreamReader(in);

        CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT
                .withDelimiter(';')
                .withFirstRecordAsHeader()
        );

        for (CSVRecord record : parser) {
            if (!isWeekend(LocalDate.parse(record.get("Time").split(" ")[0], formatter), weekendDaysList) || !holidays2019.contains(record.get("Time").split(" ")[0])) {

                Integer hour = Integer.parseInt(record.get("Time").split(" ")[1].split(":")[0]);
                double value = Double.parseDouble(record.get("processed_all_vol").replaceAll(",", "."));
                tempCountSum.computeIfAbsent(hour, k -> new ArrayList<>()).add(value);
                sum += value;
            }
        }

        if (sum == 0) {

            allStations.remove(count.getId().toString());

        } else {

            for (Map.Entry<Integer, List<Double>> meanCounts : tempCountSum.entrySet()) {
                countMean = 0.0;
                for (Double value : meanCounts.getValue()) {
                    countMean += value;
                }
                countMean = countMean / (meanCounts.getValue().size());
                hourCountsTmp[meanCounts.getKey()] = countMean.intValue();
                count.createVolume(meanCounts.getKey() + 1, countMean);
            }

        }
    }

    private boolean isWeekend(LocalDate date, List<Integer> weekendDaysList) {
        return weekendDaysList.contains(date.getDayOfWeek().getValue());
    }

    private Counts<Link> aggregateCounts(Map<String, Counts<Link>> collect) {

        Counts<Link> counts = new Counts<>();
        double[] averageCounts;

        // all stations
        for(String id : allStations) {

            averageCounts = new double[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

            // each month
            for (Map.Entry<String, Counts<Link>> countMap : collect.entrySet()) {

                for (int i = 0; i < 24; i++) {

                    averageCounts[i] = averageCounts[i] + countMap.getValue().getCount(Id.createLinkId(id)).getVolume(i + 1).getValue();

                }
            }

            for (int i = 0; i < 24; i++) {
                averageCounts[i] = averageCounts[i]/12;
            }

            System.out.println("ID: " + id + "\t" + Arrays.toString(averageCounts));

        }

        return counts;

    }

}