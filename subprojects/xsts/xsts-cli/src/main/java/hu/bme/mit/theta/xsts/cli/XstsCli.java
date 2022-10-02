package hu.bme.mit.theta.xsts.cli;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.base.Stopwatch;
import com.google.common.io.Files;
import com.google.common.io.MoreFiles;
import hu.bme.mit.theta.analysis.*;
import hu.bme.mit.theta.analysis.algorithm.SafetyResult;
import hu.bme.mit.theta.analysis.algorithm.cegar.CegarStatistics;
import hu.bme.mit.theta.analysis.expr.refinement.PruneStrategy;
import hu.bme.mit.theta.analysis.utils.ArgVisualizer;
import hu.bme.mit.theta.analysis.utils.TraceVisualizer;
import hu.bme.mit.theta.common.CliUtils;
import hu.bme.mit.theta.common.logging.ConsoleLogger;
import hu.bme.mit.theta.common.logging.Logger;
import hu.bme.mit.theta.common.logging.NullLogger;
import hu.bme.mit.theta.common.table.BasicTableWriter;
import hu.bme.mit.theta.common.table.TableWriter;
import hu.bme.mit.theta.common.visualization.Graph;
import hu.bme.mit.theta.common.visualization.writer.GraphvizWriter;
import hu.bme.mit.theta.solver.z3.Z3SolverFactory;
import hu.bme.mit.theta.xsts.XSTS;
import hu.bme.mit.theta.xsts.analysis.*;
import hu.bme.mit.theta.xsts.analysis.concretizer.TraceGenerationXstsTraceConcretizerUtil;
import hu.bme.mit.theta.xsts.analysis.concretizer.XstsStateSequence;
import hu.bme.mit.theta.xsts.analysis.concretizer.XstsTraceConcretizerUtil;
import hu.bme.mit.theta.xsts.analysis.config.XstsConfig;
import hu.bme.mit.theta.xsts.analysis.config.XstsConfigBuilder;
import hu.bme.mit.theta.xsts.analysis.config.XstsConfigBuilder.*;
import hu.bme.mit.theta.xsts.analysis.config.XstsTracegenBuilder;
import hu.bme.mit.theta.xsts.analysis.config.XstsTracegenConfig;
import hu.bme.mit.theta.xsts.dsl.XstsDslManager;
import hu.bme.mit.theta.xsts.pnml.PnmlParser;
import hu.bme.mit.theta.xsts.pnml.PnmlToXSTS;
import hu.bme.mit.theta.xsts.pnml.elements.PnmlNet;

import java.io.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class XstsCli {

	private static final String JAR_NAME = "theta-xsts-cli.jar";
	private final String[] args;
	private final TableWriter writer;

	@Parameter(names = {"--domain"}, description = "Abstract domain")
	Domain domain = Domain.PRED_CART;

	@Parameter(names = {"--refinement"}, description = "Refinement strategy")
	Refinement refinement = Refinement.SEQ_ITP;

	@Parameter(names = {"--search"}, description = "Search strategy")
	Search search = Search.BFS;

	@Parameter(names = {"--predsplit"}, description = "Predicate splitting")
	PredSplit predSplit = PredSplit.WHOLE;

	@Parameter(names = {"--model"}, description = "Path of the input XSTS model", required = true)
	String model;

	@Parameter(names = {"--property"}, description = "Input property as a string or a file (*.prop)", required = true)
	String property;

	@Parameter(names = {"--initialmarking"}, description = "Initial marking of the Petri net")
	String initialMarking="";

	@Parameter(names = "--maxenum", description = "Maximal number of explicitly enumerated successors (0: unlimited)")
	Integer maxEnum = 0;

	@Parameter(names = "--autoexpl", description = "Predicate to explicit switching strategy")
 	AutoExpl autoExpl = AutoExpl.NEWOPERANDS;

	@Parameter(names = {"--initprec"}, description = "Initial precision")
	InitPrec initPrec = InitPrec.EMPTY;

	@Parameter(names = "--prunestrategy", description = "Strategy for pruning the ARG after refinement")
	PruneStrategy pruneStrategy = PruneStrategy.LAZY;

	@Parameter(names = "--optimizestmts", description = "Turn statement optimization on or off")
	OptimizeStmts optimizeStmts = OptimizeStmts.ON;

	@Parameter(names = {"--loglevel"}, description = "Detailedness of logging")
	Logger.Level logLevel = Logger.Level.SUBSTEP;

	@Parameter(names = {"--benchmark"}, description = "Benchmark mode (only print metrics)")
	Boolean benchmarkMode = false;

	@Parameter(names = {"--cex"}, description = "Write concrete counterexample to a file")
	String cexfile = null;

	@Parameter(names = {"--header"}, description = "Print only a header (for benchmarks)", help = true)
	boolean headerOnly = false;

	@Parameter(names = "--metrics", description = "Print metrics about the XSTS without running the algorithm")
	boolean metrics = false;

	@Parameter(names = "--stacktrace", description = "Print full stack trace in case of exception")
	boolean stacktrace = false;

	@Parameter(names = "--version", description = "Display version", help = true)
	boolean versionInfo = false;

	@Parameter(names = {"--visualize"}, description = "Write proof or counterexample to file in dot format")
	String dotfile = null;

	@Parameter(names = "--tracegen", description = "Generate all possible traces of the model (instead of verification)")
	boolean tracegen = false;

	@Parameter(names = "--get-full-traces", description = "Generates more, but longer and maximal traces")
	boolean getFullTraces = false;

	@Parameter(names = "--variable-list", description = "A list of variable names (one in each line) to be included when generating traces")
	String varFile = null;

	@Parameter(names = "--no-transition-coverage", description = "Generates more, but longer and maximal traces")
	boolean noTransitionCoverage = false;

	private Logger logger;

	public XstsCli(final String[] args) {
		this.args = args;
		writer = new BasicTableWriter(System.out, ",", "\"", "\"");
	}

	public static void main(final String[] args) {
		final XstsCli mainApp = new XstsCli(args);
		mainApp.run();
	}

	private void run() {
		try {
			JCommander.newBuilder().addObject(this).programName(JAR_NAME).build().parse(args);
			logger = benchmarkMode ? NullLogger.getInstance() : new ConsoleLogger(logLevel);
		} catch (final ParameterException ex) {
			System.out.println("Invalid parameters, details:");
			System.out.println(ex.getMessage());
			ex.usage();
			return;
		}

		if (headerOnly) {
			printHeader();
			return;
		}

		if (versionInfo) {
			CliUtils.printVersion(System.out);
			return;
		}

		try {
			final Stopwatch sw = Stopwatch.createStarted();
			final XSTS xsts = loadModel();

			if(tracegen) {
				XstsTracegenConfig<? extends State, ? extends Action, ? extends Prec> tracegenConfig = new XstsTracegenBuilder(Z3SolverFactory.getInstance(), !noTransitionCoverage).logger(logger).setGetFullTraces(getFullTraces).setVarFile(varFile).build(xsts);
				tracegenConfig.check();
				List<? extends Trace<? extends State, ? extends Action>> traces = tracegenConfig.getTraces();
				final File modelFile = new File(model);
				final String tracePath = modelFile.getParent() + File.separator + "traces";
				final File traceDir = new File(tracePath);
				if(traceDir.exists()) {
					MoreFiles.deleteRecursively(traceDir.toPath());
				}
				traceDir.mkdir();

				int i = 0;

				for (Trace<? extends State, ? extends Action> trace : traces) {
					try {
						XstsStateSequence concretizedTrace = TraceGenerationXstsTraceConcretizerUtil.concretize((Trace<XstsState<?>, XstsAction>) trace, Z3SolverFactory.getInstance(), xsts);

						final File traceFile = new File(File.separator + tracePath + File.separator + Files.getNameWithoutExtension(modelFile.getName()) + "-" + i + ".trace");
						logger.write(Logger.Level.MAINSTEP, "Writing trace into file: %s%n", traceFile.getPath());
						try (PrintWriter printWriter = new PrintWriter(traceFile)) {
							printWriter.write(concretizedTrace.toString());
						}
						i++;

						logger.write(Logger.Level.SUBSTEP, "---------------------------%n");
					} catch(IllegalArgumentException | ClassCastException e) {
						logger.write(Logger.Level.SUBSTEP, "Trace is infeasible%n");
					}
				}

				sw.stop();
				logger.write(Logger.Level.MAINSTEP, "%nElapsed time: %s%n", sw.elapsed(TimeUnit.MILLISECONDS));
				return;
			}

			if (metrics) {
				XstsMetrics.printMetrics(logger, xsts);
				return;
			}

			final XstsConfig<?, ?, ?> configuration = buildConfiguration(xsts);

			final SafetyResult<?, ?> status = check(configuration);
			sw.stop();
			printResult(status, xsts, sw.elapsed(TimeUnit.MILLISECONDS));
			if (status.isUnsafe() && cexfile != null) {
				writeCex(status.asUnsafe(), xsts);
			}
			if (dotfile != null) {
				writeVisualStatus(status, dotfile);
			}
		} catch (final Throwable ex) {
			printError(ex);
			System.exit(1);
		}
	}

	private SafetyResult<?, ?> check(XstsConfig<?, ?, ?> configuration) throws Exception {
		try {
			return configuration.check();
		} catch (final Exception ex) {
			String message = ex.getMessage() == null ? "(no message)" : ex.getMessage();
			throw new Exception("Error while running algorithm: " + ex.getClass().getSimpleName() + " " + message, ex);
		}
	}

	private void printHeader() {
		Stream.of("Result", "TimeMs", "AlgoTimeMs", "AbsTimeMs", "RefTimeMs", "Iterations",
				"ArgSize", "ArgDepth", "ArgMeanBranchFactor", "CexLen", "Vars").forEach(writer::cell);
		writer.newRow();
	}

	private XSTS loadModel() throws Exception {
		InputStream propStream = null;
		try {
			if(tracegen) {
				propStream = new ByteArrayInputStream(("prop {\n" +
						"\ttrue\n" +
						"}\n").getBytes());
			}
			else if (property.endsWith(".prop")) propStream = new FileInputStream(property);
			else propStream = new ByteArrayInputStream(("prop { " + property + " }").getBytes());

			if (model.endsWith(".pnml")) {
				final PnmlNet pnmlNet = PnmlParser.parse(model,initialMarking);
				return PnmlToXSTS.createXSTS(pnmlNet, propStream);
			} else {

				try (SequenceInputStream inputStream = new SequenceInputStream(new FileInputStream(model), propStream)) {
					return XstsDslManager.createXsts(inputStream);
				}
			}

		} catch (Exception ex) {
			throw new Exception("Could not parse XSTS: " + ex.getMessage(), ex);
		} finally {
			if (propStream != null) propStream.close();
		}
	}

	private XstsConfig<?, ?, ?> buildConfiguration(final XSTS xsts) throws Exception {
		try {
			return new XstsConfigBuilder(domain, refinement, Z3SolverFactory.getInstance())
					.maxEnum(maxEnum).autoExpl(autoExpl).initPrec(initPrec).pruneStrategy(pruneStrategy)
					.search(search).predSplit(predSplit).optimizeStmts(optimizeStmts).logger(logger).build(xsts);
		} catch (final Exception ex) {
			throw new Exception("Could not create configuration: " + ex.getMessage(), ex);
		}
	}

	private void printResult(final SafetyResult<?, ?> status, final XSTS sts, final long totalTimeMs) {
		final CegarStatistics stats = (CegarStatistics) status.getStats().get();
		if (benchmarkMode) {
			writer.cell(status.isSafe());
			writer.cell(totalTimeMs);
			writer.cell(stats.getAlgorithmTimeMs());
			writer.cell(stats.getAbstractorTimeMs());
			writer.cell(stats.getRefinerTimeMs());
			writer.cell(stats.getIterations());
			writer.cell(status.getArg().size());
			writer.cell(status.getArg().getDepth());
			writer.cell(status.getArg().getMeanBranchingFactor());
			if (status.isUnsafe()) {
				writer.cell(status.asUnsafe().getTrace().length() + "");
			} else {
				writer.cell("");
			}
			writer.cell(sts.getVars().size());
			writer.newRow();
		}
	}

	private void printError(final Throwable ex) {
		final String message = ex.getMessage() == null ? "" : ex.getMessage();
		if (benchmarkMode) {
			writer.cell("[EX] " + ex.getClass().getSimpleName() + ": " + message);
			writer.newRow();
		} else {
			logger.write(Logger.Level.RESULT, "%s occurred, message: %s%n", ex.getClass().getSimpleName(), message);
			if (stacktrace) {
				final StringWriter errors = new StringWriter();
				ex.printStackTrace(new PrintWriter(errors));
				logger.write(Logger.Level.RESULT, "Trace:%n%s%n", errors.toString());
			} else {
				logger.write(Logger.Level.RESULT, "Use --stacktrace for stack trace%n");
			}
		}
	}

	private void writeCex(final SafetyResult.Unsafe<?, ?> status, final XSTS xsts) throws FileNotFoundException {

		@SuppressWarnings("unchecked") final Trace<XstsState<?>, XstsAction> trace = (Trace<XstsState<?>, XstsAction>) status.getTrace();
		final XstsStateSequence concrTrace = XstsTraceConcretizerUtil.concretize(trace, Z3SolverFactory.getInstance(), xsts);
		final File file = new File(cexfile);
		try (PrintWriter printWriter = new PrintWriter(file)) {
			printWriter.write(concrTrace.toString());
		}
	}

	private void writeVisualStatus(final SafetyResult<?, ?> status, final String filename)
			throws FileNotFoundException {
		final Graph graph = status.isSafe() ? ArgVisualizer.getDefault().visualize(status.asSafe().getArg())
				: TraceVisualizer.getDefault().visualize(status.asUnsafe().getTrace());
		GraphvizWriter.getInstance().writeFile(graph, filename);
	}

}
