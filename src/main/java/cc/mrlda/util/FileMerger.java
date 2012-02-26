package cc.mrlda.util;

import java.io.IOException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.mapred.TextOutputFormat;
import org.apache.hadoop.mapred.lib.IdentityMapper;
import org.apache.hadoop.mapred.lib.IdentityReducer;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import cc.mrlda.Settings;
import cc.mrlda.VariationalInference;

import com.google.common.base.Preconditions;
import com.sun.xml.internal.rngom.parse.compact.ParseException;

public class FileMerger extends Configured implements Tool {
  private static final Logger sLogger = Logger.getLogger(FileMerger.class);

  public static final String MERGE = "merge-tmp-dir";

  public static final String LOCAL_MERGE_OPTION = "localmerge";
  public static final boolean LOCAL_MERGE = false;
  public static final String DELETE_SOURCE_OPTION = "deletesource";
  public static final boolean DELETE_SOURCE = false;
  public static final String TEXT_FILE_INPUT_FORMAT = "textformat";
  public static final boolean TEXT_FILE_INPUT = false;

  // public static final String KEY_CLASS = "key.class";
  // public static final String VALUE_CLASS = "value.class";
  //
  // public static final String FILE_INPUT_FORMAT_CLASS = "file.input.format.class";
  // public static final String FILE_OUTPUT_FORMAT_CLASS = "file.output.format.class";

  public static final String FILE_CONTENT_DELIMITER = "";

  public static Path mergeTextFiles(String inputFiles, String outputFile, int numberOfMappers,
      boolean deleteSource) throws Exception {
    if (numberOfMappers <= 0) {
      return mergeTextFilesLocal(inputFiles, outputFile, deleteSource);
    } else {
      return mergeFilesDistribute(inputFiles, outputFile, numberOfMappers, LongWritable.class,
          Text.class, TextInputFormat.class, TextOutputFormat.class, deleteSource);
    }
  }

  /**
   * @param inputFiles a glob expression of the files to be merged
   * @param outputFile
   * @param deleteSource
   * @return
   * @throws IOException
   */
  public static Path mergeTextFilesLocal(String inputFiles, String outputFile, boolean deleteSource)
      throws IOException {
    JobConf conf = new JobConf(FileMerger.class);
    FileSystem fs = FileSystem.get(conf);

    Path inputPath = new Path(inputFiles);
    Path outputPath = new Path(outputFile);
    Preconditions.checkArgument(!fs.exists(outputPath), new IOException(
        "Destination file already exists..."));

    FileUtil.copyMerge(fs, inputPath, fs, outputPath, deleteSource, conf, FILE_CONTENT_DELIMITER);
    sLogger.info("Successfully merge " + inputPath.toString() + " to " + outputFile);

    return outputPath;
  }

  public static Path mergeSequenceFiles(String inputFiles, String outputFile, int numberOfMappers,
      Class<? extends Writable> keyClass, Class<? extends Writable> valueClass, boolean deleteSource)
      throws Exception {
    if (numberOfMappers <= 0) {
      return mergeSequenceFilesLocal(inputFiles, outputFile, keyClass, valueClass, deleteSource);
    } else {
      return mergeFilesDistribute(inputFiles, outputFile, numberOfMappers, keyClass, valueClass,
          SequenceFileInputFormat.class, SequenceFileOutputFormat.class, deleteSource);
    }
  }

  public static Path mergeSequenceFilesLocal(String inputFiles, String outputFile,
      Class<? extends Writable> keyClass, Class<? extends Writable> valueClass, boolean deleteSource)
      throws IOException, InstantiationException, IllegalAccessException {
    JobConf conf = new JobConf(FileMerger.class);
    FileSystem fs = FileSystem.get(conf);

    Path inputPath = new Path(inputFiles);
    Path outputPath = new Path(outputFile);
    Preconditions.checkArgument(!fs.exists(outputPath), new IOException(
        "Destination file already exists..."));

    FileStatus[] fileStatuses = fs.globStatus(inputPath);
    SequenceFile.Reader sequenceFileReader = null;
    SequenceFile.Writer sequenceFileWriter = null;

    Writable key, value;
    key = keyClass.newInstance();
    value = valueClass.newInstance();

    try {
      sequenceFileWriter = new SequenceFile.Writer(fs, conf, outputPath, keyClass, valueClass);

      for (FileStatus fileStatus : fileStatuses) {
        sequenceFileReader = new SequenceFile.Reader(fs, fileStatus.getPath(), conf);

        while (sequenceFileReader.next(key, value)) {
          sequenceFileWriter.append(key, value);
        }

        if (deleteSource) {
          fs.deleteOnExit(fileStatus.getPath());
        }
      }
    } finally {
      IOUtils.closeStream(sequenceFileReader);
      IOUtils.closeStream(sequenceFileWriter);
    }

    sLogger.info("Successfully merge " + inputPath.toString() + " to " + outputFile);

    return outputPath;
  }

  public static Path mergeFilesDistribute(String inputFiles, String outputFile,
      int numberOfMappers, Class<? extends Writable> keyClass,
      Class<? extends Writable> valueClass, Class<? extends FileInputFormat> fileInputClass,
      Class<? extends FileOutputFormat> fileOutputClass, boolean deleteSource) throws Exception {
    JobConf conf = new JobConf(FileMerger.class);
    conf.setJobName(FileMerger.class.getSimpleName());

    FileSystem fs = FileSystem.get(conf);

    sLogger.info("Tool: " + FileMerger.class.getSimpleName());

    sLogger.info(" - merge files from: " + inputFiles);
    sLogger.info(" - merge files to: " + outputFile);

    conf.setNumMapTasks(numberOfMappers);
    conf.setNumReduceTasks(1);

    conf.setMapperClass(IdentityMapper.class);
    conf.setReducerClass(IdentityReducer.class);

    conf.setMapOutputKeyClass(keyClass);
    conf.setMapOutputValueClass(valueClass);
    conf.setOutputKeyClass(keyClass);
    conf.setOutputValueClass(valueClass);

    conf.setInputFormat(fileInputClass);
    conf.setOutputFormat(fileOutputClass);

    Path inputPath = new Path(inputFiles);

    Path mergePath = new Path(inputPath.getParent().toString() + Path.SEPARATOR + MERGE);
    Preconditions.checkArgument(!fs.exists(mergePath), new IOException(
        "Intermedia merge directory already exists..."));

    Path outputPath = new Path(outputFile);
    Preconditions.checkArgument(!fs.exists(outputPath), new IOException(
        "Destination file already exists..."));

    FileInputFormat.setInputPaths(conf, inputPath);
    FileOutputFormat.setOutputPath(conf, mergePath);
    FileOutputFormat.setCompressOutput(conf, false);

    try {
      long startTime = System.currentTimeMillis();
      RunningJob job = JobClient.runJob(conf);
      sLogger.info("Merge Finished in " + (System.currentTimeMillis() - startTime) / 1000.0
          + " seconds");

      fs.rename(new Path(mergePath.toString() + Path.SEPARATOR + "part-00000"), outputPath);
      sLogger.info("Successfully merge " + inputFiles.toString() + " to " + outputFile);

      if (deleteSource) {
        fs.deleteOnExit(inputPath);
      }
    } finally {
      fs.deleteOnExit(mergePath);
    }

    return outputPath;
  }

  @Override
  public int run(String[] args) throws Exception {
    Options options = new Options();

    options.addOption(Settings.HELP_OPTION, false, "print the help message");
    options.addOption(OptionBuilder.withArgName(Settings.PATH_INDICATOR).hasArg()
        .withDescription("input file or directory").create(Settings.INPUT_OPTION));
    options.addOption(OptionBuilder.withArgName(Settings.PATH_INDICATOR).hasArg()
        .withDescription("output file").create(Settings.OUTPUT_OPTION));
    options
        .addOption(OptionBuilder
            .withArgName(Settings.INTEGER_INDICATOR)
            .hasArg()
            .withDescription(
                "number of mappers (default to 0 and hence local merge mode, set to positive value to enable cluster merge mode)")
            .create(Settings.MAPPER_OPTION));
    options.addOption(OptionBuilder.withArgName("property=value").hasArgs(2).withValueSeparator()
        .withDescription("assign value for given property").create("D"));
    options.addOption(TEXT_FILE_INPUT_FORMAT, false, "input file in sequence format");
    options.addOption(DELETE_SOURCE_OPTION, false, "delete sources after merging");

    int mapperTasks = 0;
    boolean deleteSource = DELETE_SOURCE;
    boolean textFileFormat = TEXT_FILE_INPUT;

    String inputPath = "";
    String outputPath = "";

    Class<? extends Writable> keyClass = LongWritable.class;
    Class<? extends Writable> valueClass = Text.class;

    // Class<? extends FileInputFormat> fileInputFormatClass = SequenceFileInputFormat.class;
    // Class<? extends FileOutputFormat> fileOutputFormatClass = SequenceFileOutputFormat.class;

    CommandLineParser parser = new GnuParser();
    HelpFormatter formatter = new HelpFormatter();
    try {
      CommandLine line = parser.parse(options, args);

      if (line.hasOption(Settings.HELP_OPTION)) {
        formatter.printHelp(FileMerger.class.getName(), options);
        System.exit(0);
      }

      if (line.hasOption(Settings.INPUT_OPTION)) {
        inputPath = line.getOptionValue(Settings.INPUT_OPTION);
      } else {
        throw new ParseException("Parsing failed due to " + Settings.INPUT_OPTION
            + " not initialized...");
      }

      if (line.hasOption(Settings.OUTPUT_OPTION)) {
        outputPath = line.getOptionValue(Settings.OUTPUT_OPTION);
      } else {
        throw new ParseException("Parsing failed due to " + Settings.OUTPUT_OPTION
            + " not initialized...");
      }

      if (line.hasOption(Settings.MAPPER_OPTION)) {
        mapperTasks = Integer.parseInt(line.getOptionValue(Settings.MAPPER_OPTION));
        if (mapperTasks <= 0) {
          sLogger.info("Warning: " + Settings.MAPPER_OPTION
              + " is not positive, merge in local model...");
          mapperTasks = 0;
        }
      }

      if (line.hasOption(DELETE_SOURCE_OPTION)) {
        deleteSource = true;
      }

      if (line.hasOption(TEXT_FILE_INPUT_FORMAT)) {
        textFileFormat = true;
      }
    } catch (ParseException pe) {
      System.err.println(pe.getMessage());
      formatter.printHelp(FileMerger.class.getName(), options);
      System.exit(0);
    } catch (NumberFormatException nfe) {
      System.err.println(nfe.getMessage());
      System.exit(0);
    }

    JobConf conf = new JobConf(FileMerger.class);
    FileSystem fs = FileSystem.get(conf);
    Path inputFiles = new Path(inputPath);
    Preconditions.checkArgument(fs.exists(inputFiles) && !fs.isFile(inputFiles),
        "Invalid input path...");
    if (!textFileFormat) {
      FileStatus[] fileStatus = fs.globStatus(inputFiles);

      SequenceFile.Reader reader = new SequenceFile.Reader(fs, fileStatus[0].getPath(),
          fs.getConf());

      try {
        keyClass = (Class<? extends Writable>) reader.getKeyClass();
        valueClass = (Class<? extends Writable>) reader.getValueClass();
        sLogger.info("Key type: " + keyClass.toString());
        sLogger.info("Value type: " + valueClass.toString() + "\n");
      } catch (Exception e) {
        throw new RuntimeException("Error in loading key/value class");
      }
    }

    if (textFileFormat) {
      mergeTextFiles(inputPath, outputPath, mapperTasks, deleteSource);
    } else {
      mergeSequenceFiles(inputPath, outputPath, mapperTasks, keyClass, valueClass, deleteSource);
    }

    return 0;
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(new Configuration(), new VariationalInference(), args);
    System.exit(res);
  }
}