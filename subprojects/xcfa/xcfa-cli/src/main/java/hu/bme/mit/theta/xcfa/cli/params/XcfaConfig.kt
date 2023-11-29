/*
 *  Copyright 2023 Budapest University of Technology and Economics
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package hu.bme.mit.theta.xcfa.cli.params

import com.beust.jcommander.Parameter
import com.google.gson.GsonBuilder
import hu.bme.mit.theta.analysis.expr.refinement.PruneStrategy
import hu.bme.mit.theta.common.logging.Logger
import hu.bme.mit.theta.frontend.ParseContext
import hu.bme.mit.theta.frontend.chc.ChcFrontend
import hu.bme.mit.theta.frontend.transformation.ArchitectureConfig
import hu.bme.mit.theta.grammar.gson.RuntimeTypeAdapterFactory
import hu.bme.mit.theta.solver.smtlib.SmtLibSolverManager
import hu.bme.mit.theta.xcfa.analysis.ErrorDetection
import hu.bme.mit.theta.xcfa.model.XCFA
import hu.bme.mit.theta.xcfa.passes.LbePass
import java.io.File
import java.nio.file.Paths


interface Config {

    fun getObjects(): Set<Config> = setOf(this)
    fun update(): Boolean = false
    fun registerRuntimeTypeAdapters(gsonBuilder: GsonBuilder) {}
}

interface SpecializableConfig<T : Config> : Config {

    val specConfig: T?
    override fun getObjects(): Set<Config> = setOf(this) union (specConfig?.getObjects() ?: setOf())
    fun createSpecConfig()
    override fun update(): Boolean = specConfig?.update() ?: (createSpecConfig().run { specConfig?.update() ?: false })
    override fun registerRuntimeTypeAdapters(gsonBuilder: GsonBuilder)
}

data class XcfaConfig<F: SpecFrontendConfig, B: SpecBackendConfig>(
    val inputConfig: InputConfig = InputConfig(),
    val frontendConfig: FrontendConfig<F> = FrontendConfig(),
    val backendConfig: BackendConfig<B> = BackendConfig(),
    val outputConfig: OutputConfig = OutputConfig(),
    val debugConfig: DebugConfig = DebugConfig(),
) : Config {

    override fun getObjects(): Set<Config> {
        return inputConfig.getObjects() union frontendConfig.getObjects() union backendConfig.getObjects() union outputConfig.getObjects() union debugConfig.getObjects()
    }

    override fun update(): Boolean =
        listOf(inputConfig, frontendConfig, backendConfig, outputConfig, debugConfig).any { it.update() }

    override fun registerRuntimeTypeAdapters(gsonBuilder: GsonBuilder) {
        listOf(inputConfig, frontendConfig, backendConfig, outputConfig,
            debugConfig).forEach { it.registerRuntimeTypeAdapters(gsonBuilder) }
    }
}

data class InputConfig(
    @Parameter(names = ["--input"], description = "Path of the input C program", required = true)
    var input: File? = null,

    @Parameter(names = ["--xcfa-w-ctx"], description = "XCFA and ParseContext (will overwrite --input when given)")
    var xcfaWCtx: Pair<XCFA, ParseContext>? = null,

    @Parameter(names = ["--property-file"], description = "Path of the property file (will overwrite --property when given)")
    var propertyFile: File? = null,

    @Parameter(names = ["--property"], description = "Property")
    var property: ErrorDetection = ErrorDetection.ERROR_LOCATION
) : Config

interface SpecFrontendConfig : Config
data class FrontendConfig<T: SpecFrontendConfig>(
    @Parameter(names = ["--lbe"], description = "Level of LBE (NO_LBE, LBE_LOCAL, LBE_SEQ, LBE_FULL)")
    var lbeLevel: LbePass.LbeLevel = LbePass.LbeLevel.LBE_SEQ,

    @Parameter(names = ["--unroll"], description = "Max number of loop iterations to unroll")
    var loopUnroll: Int = 50,

    @Parameter(names = ["--input-type"], description = "Format of the input")
    var inputType: InputType = InputType.C,

    override var specConfig: T? = null
) : SpecializableConfig<T> {

    override fun createSpecConfig() {
        specConfig = when (inputType) {
            InputType.C -> CFrontendConfig() as T
            InputType.LLVM -> null
            InputType.JSON -> null
            InputType.DSL -> null
            InputType.CHC -> CHCFrontendConfig() as T
        }
    }

    override fun registerRuntimeTypeAdapters(gsonBuilder: GsonBuilder) {
        gsonBuilder.registerTypeAdapterFactory(RuntimeTypeAdapterFactory
            .of(SpecFrontendConfig::class.java, "type")
            .registerSubtype(CFrontendConfig::class.java, "c")
            .registerSubtype(CHCFrontendConfig::class.java, "chc"))
    }
}

data class CFrontendConfig(
    @Parameter(names = ["--arithmetic"], description = "Arithmetic type (efficient, bitvector, integer)")
    var arithmetic: ArchitectureConfig.ArithmeticType = ArchitectureConfig.ArithmeticType.efficient,
) : SpecFrontendConfig

data class CHCFrontendConfig(
    @Parameter(names = ["--chc-transformation"], description = "Direction of transformation from CHC to XCFA")
    var chcTransformation: ChcFrontend.ChcTransformation = ChcFrontend.ChcTransformation.PORTFOLIO,
) : SpecFrontendConfig

interface SpecBackendConfig : Config

data class BackendConfig<T: SpecBackendConfig>(
    @Parameter(names = ["--backend"], description = "Backend analysis to use")
    var backend: Backend = Backend.CEGAR,

    @Parameter(names = ["--smt-home"], description = "The path of the solver registry")
    var solverHome: String = SmtLibSolverManager.HOME.toAbsolutePath().toString(),

    @Parameter(names = ["--timeout-ms"], description = "Timeout for verification, use 0 for no timeout")
    var timeoutMs: Long = 0,

    @Parameter(names = ["--in-process"], description = "Run analysis in process")
    var inProcess: Boolean = false,

    override var specConfig: T? = null
) : SpecializableConfig<T> {

    override fun createSpecConfig() {
        specConfig = when (backend) {
            Backend.CEGAR -> CegarConfig() as T
            Backend.BOUNDED -> BoundedConfig() as T
            Backend.LAZY -> null
            Backend.PORTFOLIO -> PortfolioConfig() as T
            Backend.NONE -> null
        }
    }

    override fun registerRuntimeTypeAdapters(gsonBuilder: GsonBuilder) {
        gsonBuilder.registerTypeAdapterFactory(RuntimeTypeAdapterFactory
            .of(SpecBackendConfig::class.java, "type")
            .registerSubtype(CegarConfig::class.java, "cegar")
            .registerSubtype(BoundedConfig::class.java, "bounded")
            .registerSubtype(PortfolioConfig::class.java, "portfolio"))
    }
}

data class CegarConfig(
    @Parameter(names = ["--initprec"], description = "Initial precision")
    var initPrec: InitPrec = InitPrec.EMPTY,

    @Parameter(names = ["--por-level"], description = "POR dependency level")
    var porLevel: POR = POR.NOPOR,

    @Parameter(names = ["--por-seed"], description = "Random seed used for DPOR")
    var porRandomSeed: Int = -1,

    @Parameter(names = ["--coi"], description = "Enable ConeOfInfluence")
    var coi: ConeOfInfluenceMode = ConeOfInfluenceMode.NO_COI,

    @Parameter(names = ["--cex-monitor"], description = "Option to enable CexMonitor")
    var cexMonitor: CexMonitorOptions = CexMonitorOptions.DISABLE,

    val abstractorConfig: CegarAbstractorConfig = CegarAbstractorConfig(),
    val refinerConfig: CegarRefinerConfig = CegarRefinerConfig()
) : SpecBackendConfig {

    override fun getObjects(): Set<Config> {
        return super.getObjects() union abstractorConfig.getObjects() union refinerConfig.getObjects()
    }

    override fun update(): Boolean =
        listOf(abstractorConfig, refinerConfig).any { it.update() }

    override fun registerRuntimeTypeAdapters(gsonBuilder: GsonBuilder) {
        listOf(abstractorConfig, refinerConfig).forEach { it.registerRuntimeTypeAdapters(gsonBuilder) }
    }
}

data class CegarAbstractorConfig(
    @Parameter(names = ["--abstraction-solver"], description = "Abstraction solver name")
    var abstractionSolver: String = "Z3",

    @Parameter(names = ["--validate-abstraction-solver"],
        description = "Activates a wrapper, which validates the assertions in the solver in each (SAT) check. Filters some solver issues.")
    var validateAbstractionSolver: Boolean = false,

    @Parameter(names = ["--domain"], description = "Abstraction domain")
    var domain: Domain = Domain.EXPL,

    @Parameter(names = ["--maxenum"],
        description = "How many successors to enumerate in a transition. Only relevant to the explicit domain. Use 0 for no limit.")
    var maxEnum: Int = 1,

    @Parameter(names = ["--search"], description = "Search strategy")
    var search: Search = Search.ERR,
) : Config

data class CegarRefinerConfig(
    @Parameter(names = ["--refinement-solver"], description = "Refinement solver name")
    var refinementSolver: String = "Z3",

    @Parameter(names = ["--validate-refinement-solver"],
        description = "Activates a wrapper, which validates the assertions in the solver in each (SAT) check. Filters some solver issues.")
    var validateRefinementSolver: Boolean = false,

    @Parameter(names = ["--refinement"], description = "Refinement strategy")
    var refinement: Refinement = Refinement.SEQ_ITP,

    @Parameter(names = ["--predsplit"], description = "Predicate splitting (for predicate abstraction)")
    var exprSplitter: ExprSplitterOptions = ExprSplitterOptions.WHOLE,

    @Parameter(names = ["--prunestrategy"], description = "Strategy for pruning the ARG after refinement")
    var pruneStrategy: PruneStrategy = PruneStrategy.LAZY,
) : Config

data class BoundedConfig(
    @Parameter(names = ["--max-bound"], description = "Maximum bound to check. Use 0 for no limit.")
    var maxBound: Int = 0,

    val bmcConfig: BMCConfig = BMCConfig(),
    val indConfig: InductionConfig = InductionConfig(),
    val itpConfig: InterpolationConfig = InterpolationConfig(),
) : SpecBackendConfig {

    override fun getObjects(): Set<Config> {
        return super.getObjects() union bmcConfig.getObjects() union indConfig.getObjects() union itpConfig.getObjects()
    }

    override fun update(): Boolean =
        listOf(bmcConfig, indConfig, itpConfig).any { it.update() }

    override fun registerRuntimeTypeAdapters(gsonBuilder: GsonBuilder) {
        listOf(bmcConfig, indConfig, itpConfig).forEach { it.registerRuntimeTypeAdapters(gsonBuilder) }
    }
}

data class BMCConfig(
    @Parameter(names = ["--no-bmc"], description = "Disable SAT check")
    var enable: Boolean = false,

    @Parameter(names = ["--bmc-solver"], description = "BMC solver name")
    var bmcSolver: String = "Z3",

    @Parameter(names = ["--validate-bmc-solver"],
        description = "Activates a wrapper, which validates the assertions in the solver in each (SAT) check. Filters some solver issues.")
    var validateBMCSolver: Boolean = false,
) : Config

data class InductionConfig(
    @Parameter(names = ["--no-induction"], description = "Disable induction check")
    var enable: Boolean = false,

    @Parameter(names = ["--induction-solver", "--ind-solver"], description = "Induction solver name")
    var indSolver: String = "Z3",

    @Parameter(names = ["--validate-induction-solver"],
        description = "Activates a wrapper, which validates the assertions in the solver in each (SAT) check. Filters some solver issues.")
    var validateIndSolver: Boolean = false,

    @Parameter(names = ["--ind-min-bound"],
        description = "Start induction after reaching this bound")
    var indMinBound: Int = 0,

    @Parameter(names = ["--ind-frequency"],
        description = "Frequency of induction check")
    var indFreq: Int = 1,
) : Config

data class InterpolationConfig(
    @Parameter(names = ["--no-interpolation"], description = "Disable interpolation check")
    var enable: Boolean = false,

    @Parameter(names = ["--interpolation-solver", "--itp-solver"], description = "Interpolation solver name")
    var itpSolver: String = "Z3",

    @Parameter(names = ["--validate-interpolation-solver"],
        description = "Activates a wrapper, which validates the assertions in the solver in each (SAT) check. Filters some solver issues.")
    var validateItpSolver: Boolean = false,

    ) : Config

data class PortfolioConfig(
    @Parameter(names = ["--portfolio"], description = "Portfolio to run")
    var portfolio: String = "COMPLEX",
) : SpecBackendConfig

data class OutputConfig(
    @Parameter(names = ["--version"], description = "Display version", help = true)
    var versionInfo: Boolean = false,

    @Parameter(names = ["--output-directory"], description = "Specify the directory where the result files are stored")
    var resultFolder: File = Paths.get("").toFile(),

    val cOutputConfig: COutputConfig = COutputConfig(),
    val xcfaOutputConfig: XcfaOutputConfig = XcfaOutputConfig(),
    val witnessConfig: WitnessConfig = WitnessConfig(),
    val argConfig: ArgConfig = ArgConfig(),
) : Config {

    override fun getObjects(): Set<Config> {
        return super.getObjects() union cOutputConfig.getObjects() union xcfaOutputConfig.getObjects() union witnessConfig.getObjects() union argConfig.getObjects()
    }

    override fun update(): Boolean =
        listOf(cOutputConfig, xcfaOutputConfig, witnessConfig, argConfig).any { it.update() }

    override fun registerRuntimeTypeAdapters(gsonBuilder: GsonBuilder) {
        listOf(cOutputConfig, xcfaOutputConfig, witnessConfig, argConfig).forEach { it.registerRuntimeTypeAdapters(gsonBuilder) }
    }
}

data class XcfaOutputConfig(
    @Parameter(names = ["--disable-xcfa-serialization"])
    var disable: Boolean = false,
) : Config

data class COutputConfig(
    @Parameter(names = ["--disable-c-serialization"])
    var disable: Boolean = false,

    @Parameter(names = ["--to-c-use-arrays"])
    var useArr: Boolean = false,

    @Parameter(names = ["--to-c-use-exact-arrays"])
    var useExArr: Boolean = false,

    @Parameter(names = ["--to-c-use-ranges"])
    var useRange: Boolean = false
) : Config

data class WitnessConfig(
    @Parameter(names = ["--disable-witness-generation"])
    var disable: Boolean = false,

    @Parameter(names = ["--cex-solver"], description = "Concretizer solver name")
    var concretizerSolver: String = "Z3",

    @Parameter(names = ["--validate-cex-solver"],
        description = "Activates a wrapper, which validates the assertions in the solver in each (SAT) check. Filters some solver issues.")
    var validateConcretizerSolver: Boolean = false
) : Config

data class ArgConfig(
    @Parameter(names = ["--disable-arg-generation"])
    var disable: Boolean = false,
) : Config

data class DebugConfig(
    @Parameter(names = ["--debug"], description = "Debug mode (not exiting when encountering an exception)")
    var debug: Boolean = false,

    @Parameter(names = ["--stacktrace"], description = "Print full stack trace in case of exception")
    var stacktrace: Boolean = false,

    @Parameter(names = ["--loglevel"], description = "Detailedness of logging")
    var logLevel: Logger.Level = Logger.Level.MAINSTEP,

    @Parameter(names = ["--arg-debug"],
        description = "ARG debug mode (use the web-based debugger for ARG visualization)")
    var argdebug: Boolean = false,

    @Parameter(names = ["--arg-to-file"],
        description = "Visualize the resulting file here: https://ftsrg-edu.github.io/student-sisak-argviz/")
    var argToFile: Boolean = false
) : Config