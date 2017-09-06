package fr.acinq.eclair

import java.io.File

import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.blockchain.SpvWatcher
import fr.acinq.eclair.blockchain.fee.{BitpayInsightFeeProvider, ConstantFeeProvider}
import fr.acinq.eclair.blockchain.spv.BitcoinjKit
import fr.acinq.eclair.blockchain.wallet.{BitcoinjWallet, EclairWallet}
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.io.Switchboard
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router._
import grizzled.slf4j.Logging

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}


/**
  * Created by PM on 25/01/2016.
  */
class Setup(datadir: File, wallet_opt: Option[EclairWallet] = None, overrideDefaults: Config = ConfigFactory.empty(), actorSystem: ActorSystem = ActorSystem()) extends Logging {

  logger.info(s"hello!")
  logger.info(s"version=${getClass.getPackage.getImplementationVersion} commit=${getClass.getPackage.getSpecificationVersion}")
  val config = NodeParams.loadConfiguration(datadir, overrideDefaults)

  val spv = config.getBoolean("spv")

  implicit val system = actorSystem
  implicit val timeout = Timeout(30 seconds)
  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global

  val (chain, chainHash, bitcoin) = if (spv) {
    logger.warn("EXPERIMENTAL SPV MODE ENABLED!!!")
    val chain = config.getString("chain")
    val chainHash = chain match {
      case "regtest" => Block.RegtestGenesisBlock.blockId
      case "test" => Block.TestnetGenesisBlock.blockId
    }

    val bitcoinjKit = new BitcoinjKit(chain, datadir)
    (chain, chainHash, Left(bitcoinjKit))
  } else ???

  val nodeParams = NodeParams.makeNodeParams(datadir, config, chainHash)
  logger.info(s"using chain=$chain chainHash=$chainHash")
  logger.info(s"nodeid=${nodeParams.privateKey.publicKey.toBin} alias=${nodeParams.alias}")

  def bootstrap: Future[Kit] = Future {

    val defaultFeeratePerKb = config.getLong("default-feerate-per-kb")
    Globals.feeratePerKw.set(feerateKb2Kw(defaultFeeratePerKb))
    logger.info(s"initial feeratePerKw=${Globals.feeratePerKw.get()}")
    val feeProvider = chain match {
      case "regtest" => new ConstantFeeProvider(defaultFeeratePerKb)
      case _ => new BitpayInsightFeeProvider()
    }
    system.scheduler.schedule(0 seconds, 10 minutes)(feeProvider.getFeeratePerKB.map {
      case feeratePerKB =>
        Globals.feeratePerKw.set(feerateKb2Kw(feeratePerKB))
        logger.info(s"current feeratePerKw=${Globals.feeratePerKw.get()}")
    })

    val watcher = bitcoin match {
      case Left(bitcoinj) =>
        bitcoinj.startAsync()
        system.actorOf(SimpleSupervisor.props(SpvWatcher.props(bitcoinj), "watcher", SupervisorStrategy.Resume))
      case _ => ???
    }

    val wallet = bitcoin match {
      case _ if wallet_opt.isDefined => wallet_opt.get
      case Left(bitcoinj) => new BitcoinjWallet(bitcoinj.initialized.map(_ => bitcoinj.wallet()))
      case _ => ???
    }
    wallet.getFinalAddress.map {
      case address => logger.info(s"initial wallet address=$address")
    }

    val paymentHandler = system.actorOf(SimpleSupervisor.props(config.getString("payment-handler") match {
      case "local" => LocalPaymentHandler.props(nodeParams)
      case "noop" => Props[NoopPaymentHandler]
    }, "payment-handler", SupervisorStrategy.Resume))
    val register = system.actorOf(SimpleSupervisor.props(Props(new Register), "register", SupervisorStrategy.Resume))
    val relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams.privateKey, paymentHandler), "relayer", SupervisorStrategy.Resume))
    val router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher), "router", SupervisorStrategy.Resume))
    val switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, watcher, router, relayer, wallet), "switchboard", SupervisorStrategy.Resume))
    val paymentInitiator = system.actorOf(SimpleSupervisor.props(PaymentInitiator.props(nodeParams.privateKey.publicKey, router, register), "payment-initiator", SupervisorStrategy.Restart))

    val kit = Kit(
      nodeParams = nodeParams,
      system = system,
      watcher = watcher,
      paymentHandler = paymentHandler,
      register = register,
      relayer = relayer,
      router = router,
      switchboard = switchboard,
      paymentInitiator = paymentInitiator,
      wallet = wallet)

    kit
  }

}

case class Kit(nodeParams: NodeParams,
               system: ActorSystem,
               watcher: ActorRef,
               paymentHandler: ActorRef,
               register: ActorRef,
               relayer: ActorRef,
               router: ActorRef,
               switchboard: ActorRef,
               paymentInitiator: ActorRef,
               wallet: EclairWallet)

