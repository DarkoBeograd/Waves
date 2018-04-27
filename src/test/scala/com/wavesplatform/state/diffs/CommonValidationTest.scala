package com.wavesplatform.state.diffs

import com.wavesplatform.db.WithState
import com.wavesplatform.features.{BlockchainFeature, BlockchainFeatures}
import com.wavesplatform.lang.v1.Terms.Typed
import com.wavesplatform.settings.{Constants, FunctionalitySettings}
import com.wavesplatform.state.EitherExt2
import com.wavesplatform.{NoShrink, TransactionGen}
import org.scalacheck.Gen
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}
import scorex.lagonaki.mocks.TestBlock
import scorex.settings.TestFunctionalitySettings
import scorex.transaction.assets.{IssueTransaction, SponsorFeeTransaction, TransferTransaction}
import scorex.transaction.smart.SetScriptTransaction
import scorex.transaction.smart.script.v1.ScriptV1
import scorex.transaction.{GenesisTransaction, Transaction}

class CommonValidationTest extends PropSpec with PropertyChecks with Matchers with TransactionGen with WithState with NoShrink {

  property("disallows double spending") {
    val preconditionsAndPayment: Gen[(GenesisTransaction, TransferTransaction)] = for {
      master    <- accountGen
      recipient <- otherAccountGen(candidate = master)
      ts        <- positiveIntGen
      genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).right.get
      transfer: TransferTransaction <- wavesTransferGeneratorP(master, recipient)
    } yield (genesis, transfer)

    forAll(preconditionsAndPayment) {
      case ((genesis, transfer)) =>
        assertDiffEi(Seq(TestBlock.create(Seq(genesis, transfer))), TestBlock.create(Seq(transfer))) { blockDiffEi =>
          blockDiffEi should produce("AlreadyInTheState")
        }

        assertDiffEi(Seq(TestBlock.create(Seq(genesis))), TestBlock.create(Seq(transfer, transfer))) { blockDiffEi =>
          blockDiffEi should produce("AlreadyInTheState")
        }
    }
  }

  property("sponsored transactions should work with smart accounts") {
    val settings = createSettings(
      BlockchainFeatures.FeeSponsorship -> 0,
      BlockchainFeatures.SmartAccounts  -> 0
    )

    forAll(sponsorAndSetScriptGen) {
      case (genesisTxs, sponsorTx, setScriptTx, transferTx) =>
        withStateAndHistory(settings) { blockchain =>
          val genesisBlock     = TestBlock.create(genesisTxs :+ sponsorTx :+ setScriptTx)
          val preconditionDiff = BlockDiffer.fromBlock(settings, blockchain, None, genesisBlock).explicitGet()
          blockchain.append(preconditionDiff, genesisBlock)

          val r = CommonValidation.checkFee(blockchain, settings, 1, transferTx)
          r should produce("Transactions from scripted accounts require Waves as fee")
        }
    }
  }

  property("sponsored transactions should work without smart accounts") {
    val settings = createSettings(BlockchainFeatures.FeeSponsorship -> 0)

    forAll(sponsorAndSetScriptGen) {
      case (genesisTxs, sponsorTx, _, transferTx) =>
        withStateAndHistory(settings) { blockchain =>
          val genesisBlock     = TestBlock.create(genesisTxs :+ sponsorTx)
          val preconditionDiff = BlockDiffer.fromBlock(settings, blockchain, None, genesisBlock).explicitGet()
          blockchain.append(preconditionDiff, genesisBlock)

          val r = CommonValidation.checkFee(blockchain, settings, 1, transferTx)
          r shouldBe 'right
        }
    }
  }

  property("smart accounts should work without sponsored transactions") {
    val settings = createSettings(BlockchainFeatures.SmartAccounts -> 0)

    forAll(sponsorAndSetScriptGen) {
      case (genesisTxs, _, setScriptTx, transferTx) =>
        withStateAndHistory(settings) { blockchain =>
          val genesisBlock     = TestBlock.create(genesisTxs :+ setScriptTx)
          val preconditionDiff = BlockDiffer.fromBlock(settings, blockchain, None, genesisBlock).explicitGet()
          blockchain.append(preconditionDiff, genesisBlock)

          val r = CommonValidation.checkFee(blockchain, settings, 1, transferTx)
          r should produce("Transactions from scripted accounts require Waves as fee")
        }
    }
  }

  private val sponsorAndSetScriptGen = for {
    richAcc      <- accountGen
    recipientAcc <- accountGen
    ts = System.currentTimeMillis()
  } yield {
    val genesisTx = GenesisTransaction.create(richAcc, ENOUGH_AMT, ts).explicitGet()

    val issueTx = IssueTransaction
      .create(richAcc, "test".getBytes(), "desc".getBytes(), Long.MaxValue, 2, reissuable = false, Constants.UnitsInWave, ts)
      .explicitGet()

    val transferWavesTx = TransferTransaction
      .create(None, richAcc, recipientAcc, 10 * Constants.UnitsInWave, ts, None, 1 * Constants.UnitsInWave, Array.emptyByteArray)
      .explicitGet()

    val transferAssetTx = TransferTransaction
      .create(Some(issueTx.id()), richAcc, recipientAcc, 100, ts, None, 1 * Constants.UnitsInWave, Array.emptyByteArray)
      .explicitGet()

    val sponsorTx = SponsorFeeTransaction
      .create(1, richAcc, issueTx.id(), Some(10), Constants.UnitsInWave, ts)
      .explicitGet()

    val setScriptTx = SetScriptTransaction
      .selfSigned(
        SetScriptTransaction.supportedVersions.head,
        recipientAcc,
        Some(ScriptV1(Typed.TRUE).explicitGet()),
        1 * Constants.UnitsInWave,
        ts
      )
      .explicitGet()

    val transferAssetsBackTx = TransferTransaction
      .create(
        Some(issueTx.id()),
        recipientAcc,
        richAcc,
        1,
        ts,
        Some(issueTx.id()),
        10,
        Array.emptyByteArray
      )
      .explicitGet()

    (Vector[Transaction](genesisTx, issueTx, transferWavesTx, transferAssetTx), sponsorTx, setScriptTx, transferAssetsBackTx)
  }

  private def createSettings(preActivatedFeatures: (BlockchainFeature, Int)*): FunctionalitySettings =
    TestFunctionalitySettings.Enabled
      .copy(
        preActivatedFeatures = preActivatedFeatures.map { case (k, v) => k.id -> v }(collection.breakOut),
        blocksForFeatureActivation = 1,
        featureCheckBlocksPeriod = 1
      )

}
