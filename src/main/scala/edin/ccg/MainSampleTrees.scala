package edin.ccg

import java.io.{File, PrintWriter}
import java.lang.management.ManagementFactory
import java.text.SimpleDateFormat
import java.util.Date

import edin.ccg.parsing.{Parser, ParserState, RevealingModel}
import edin.nn.{DyFunctions, DynetSetup}
import edin.search.{EnsamblePredictionState, NeuralSampler}
import edin.ccg.representation.tree.TreeNode
import edin.nn.embedder.SequenceEmbedderELMo

import scala.io.Source
import scala.util.{Failure, Success, Try}

object MainSampleTrees {

  case class CMDargs(
                      sentsFile          : String         = null,
                      samples_dir        : String         = null,
                      disc_model_dirs    : List[String]   = null,
                      samples            : Int            = 100,
                      alpha              : Double         = 1.0,

                      dynet_mem          : String         = null,
                      dynet_autobatch    : Int            =    0,
                      dynet_weight_decay : Float          = 0.0f,
                      dynet_gpus         : List[Int]      = List()
                    )

  def main(args:Array[String]) : Unit = {
    val parser = new scopt.OptionParser[CMDargs](PROGRAM_NAME) {
      head(PROGRAM_NAME, PROGRAM_VERSION.toString)

      opt[ String      ]( "sents_file"         ).action((x,c) => c.copy( sentsFile            = x        )).required()

      opt[ String      ]( "samples_dir"        ).action((x,c) => c.copy( samples_dir          = x        )).required()

      opt[ Seq[String] ]( "disc_model_dirs"    ).action((x,c) => c.copy( disc_model_dirs      = x.toList )).required()

      opt[ Int         ]( "samples"            ).action((x,c) => c.copy( samples              = x        )).required()
      opt[ Double      ]( "alpha"              ).action((x,c) => c.copy( alpha                = x        ))

      opt[ Int         ]( "dynet-autobatch"    ).action((x,c) => c.copy( dynet_autobatch      = x        ))
      opt[ String      ]( "dynet-mem"          ).action((x,c) => c.copy( dynet_mem            = x        ))
      opt[ Seq[Int]    ]( "dynet-gpus"         ).action((x,c) => c.copy( dynet_gpus           = x.toList ))

      help("help").text("prints this usage text")
    }

    parser.parse(args, CMDargs()) match {
      case Some(cmd_args) =>

        System.err.println("\nprocess identity: "+ManagementFactory.getRuntimeMXBean.getName+"\n")
        val ft = new SimpleDateFormat ("HH:mm dd.MM.yyyy")
        System.err.println(s"sampling started at ${ft.format(new Date())}")

        DynetSetup.init_dynet(
          cmd_args.dynet_mem,
          cmd_args.dynet_weight_decay,
          cmd_args.dynet_autobatch,
          cmd_args.dynet_gpus)

        val disc_models = cmd_args.disc_model_dirs.map { modelDir =>
          val model = new RevealingModel()
          model.loadFromModelDir(modelDir)
          model
        }

        if(! new File(cmd_args.samples_dir).exists())
          new File(cmd_args.samples_dir).mkdirs()

        for((line, lineId) <- Source.fromFile(cmd_args.sentsFile).getLines().zipWithIndex){
          System.err.print(s"processing $lineId ")
          val pw = new PrintWriter(s"${cmd_args.samples_dir}/$lineId")

          val words = line.split(" +").toList
          for(sampleId <- 1 to cmd_args.samples){
            if(sampleId%10 == 0)
              System.err.print(".")
            val (logD, tree) = getSampleScoresPairRepeatUntilSuccess(words, disc_models, cmd_args.alpha)
            pw.println(logD+" "+tree.toCCGbankString)
          }
          System.err.println()

          pw.close()
        }

        System.err.println(s"sampling finished at ${ft.format(new Date())}")

        SequenceEmbedderELMo.endServer()

      case None =>
        System.err.println("You didn't specify all the required arguments")
        System.exit(-1)
    }
  }

  private def getSampleScoresPairRepeatUntilSuccess(words:List[String], discModels:List[RevealingModel], alpha:Double) : (Double, TreeNode) =
    Try(getSampleScoresPair(words, discModels, alpha)) match {
      case Success(value) => value
      case Failure(exception) =>
        exception.printStackTrace()
        println(exception.getMessage)
        println(exception)
        Thread.sleep(3000)
        getSampleScoresPairRepeatUntilSuccess(words, discModels, alpha)
    }

  private def getSampleScoresPair(words:List[String], discModels:List[RevealingModel], alpha:Double) : (Double, TreeNode) = {
    DynetSetup.cg_renew()
    DyFunctions.disableAllDropout()

    val discParserState = new EnsamblePredictionState(
      states = discModels.map{model => Parser.initParserState(words)(model)},
      alpha = alpha
    )
    val (predictedPaserState, discScore, _) = NeuralSampler.sample(discParserState, maxExpansion = words.size*10)

    val conf = predictedPaserState.unwrapState[ParserState].conf
    Try(conf.extractTree) match {
      case Success(tree) => (discScore, tree)
      case Failure(e) =>
        conf.saveVisualStackState("sampling")
        throw e
    }
  }

}

