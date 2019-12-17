package org.bitcoins.core.psbt

import org.bitcoins.core.crypto._
import org.bitcoins.core.hd.BIP32Path
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.protocol.{CompactSizeUInt, NetworkElement}
import org.bitcoins.core.{byteVectorOrdering, psbt}
import org.bitcoins.core.psbt.GlobalPSBTRecord._
import org.bitcoins.core.psbt.InputPSBTRecord._
import org.bitcoins.core.psbt.PSBTGlobalKeyId._
import org.bitcoins.core.psbt.PSBTInputKeyId._
import org.bitcoins.core.script.crypto.HashType
import org.bitcoins.core.serializers.script.RawScriptWitnessParser
import org.bitcoins.core.util.Factory
import org.bitcoins.core.wallet.utxo.{
  BitcoinUTXOSpendingInfo,
  ConditionalPath,
  UTXOSpendingInfo
}
import scodec.bits._

import scala.annotation.tailrec

case class PSBT(
    globalMap: GlobalPSBTMap,
    inputMaps: Vector[InputPSBTMap],
    outputMaps: Vector[OutputPSBTMap])
    extends NetworkElement {
  require(
    globalMap
      .getRecords(UnsignedTransactionKeyId)
      .size == 1)
  require(inputMaps.size == transaction.inputs.size)
  require(outputMaps.size == transaction.outputs.size)

  // Need to define these so when we compare the PSBTs
  // the map lexicographical ordering is enforced
  def ==(p: PSBT): Boolean = this.bytes == p.bytes
  def !=(p: PSBT): Boolean = !(this == p)

  private val inputBytes: ByteVector =
    inputMaps.foldLeft(ByteVector.empty)(_ ++ _.bytes)

  private val outputBytes: ByteVector =
    outputMaps.foldLeft(ByteVector.empty)(_ ++ _.bytes)

  val bytes: ByteVector = PSBT.magicBytes ++
    globalMap.bytes ++
    inputBytes ++
    outputBytes

  def transaction: Transaction =
    globalMap
      .getRecords[UnsignedTransaction](UnsignedTransactionKeyId)
      .head
      .transaction

  def isFinalized: Boolean = inputMaps.forall(_.isFinalized)

  def version: UInt32 = {
    val vec = globalMap.getRecords[Version](VersionKeyId)
    if (vec.isEmpty) { // If there is no version is it assumed 0
      UInt32.zero
    } else {
      vec.head.version
    }
  }

  /**
    * Combiner defined by https://github.com/bitcoin/bips/blob/master/bip-0174.mediawiki#combiner
    * Takes another PSBT and adds all records that are not contained in this PSBT
    * A record's distinctness is determined by its key
    * @param other PSBT to be combined with
    * @return A PSBT with the combined data of the two PSBTs
    */
  def combinePSBT(other: PSBT): PSBT = {
    require(this.transaction.txId == other.transaction.txId,
            "Can only combine PSBTs with the same global transaction.")

    val global = this.globalMap.combine(other.globalMap)
    val inputs = this.inputMaps
      .zip(other.inputMaps)
      .map(input => input._1.combine(input._2))
    val outputs = this.outputMaps
      .zip(other.outputMaps)
      .map(output => output._1.combine(output._2))

    PSBT(global, inputs, outputs)
  }

  /**
    * Takes the InputPSBTMap at the given index and returns a UTXOSpendingInfo
    * that can be used to sign the input
    * @param index index of the InputPSBTMap
    * @param signers Signers that will be used to sign the input
    * @param conditionalPath Path that should be used for the script
    * @return A corresponding UTXOSpendingInfo
    */
  def getUTXOSpendingInfo(
      index: Int,
      signers: Seq[Sign],
      conditionalPath: ConditionalPath = ConditionalPath.NoConditionsLeft): UTXOSpendingInfo = {
    require(index >= 0 && index < inputMaps.size,
            s"Index must be within 0 and the number of inputs, got: $index")
    inputMaps(index).toUTXOSpendingInfo(transaction.inputs(index),
                                        signers,
                                        conditionalPath)
  }

  /**
    * @param tx Transaction to add to PSBT
    * @param i index of inputMap to add tx to
    * @return PSBT with added tx
    */
  def addTransaction(tx: Transaction, i: Int): PSBT = {
    require(
      i < inputMaps.size,
      s"i must be less than the number of input maps present in the psbt, $i >= ${inputMaps.size}")
    if (inputMaps(i).isFinalized) {
      this
    } else {
      var elements = inputMaps(i).elements
      val txIn = transaction.inputs(i)

      if (txIn.previousOutput.vout.toInt < tx.outputs.size) {
        val out = tx.outputs(txIn.previousOutput.vout.toInt)

        val outIsWitnessScript =
          WitnessScriptPubKey.isWitnessScriptPubKey(out.scriptPubKey.asm)
        val hasWitScript =
          inputMaps(i)
            .getRecords[InputPSBTRecord.WitnessScript](WitnessScriptKeyId)
            .size == 1

        if (outIsWitnessScript || hasWitScript) {
          elements = elements.filterNot(_.key.startsWith(hex"01")) :+ WitnessUTXO(
            out)
        } else {
          elements = elements.filterNot(_.key.startsWith(hex"00")) :+ NonWitnessOrUnknownUTXO(
            tx)
        }
      }

      val newInputMaps =
        inputMaps.patch(i, Seq(InputPSBTMap(elements)), 1)
      PSBT(globalMap, newInputMaps, outputMaps)
    }
  }

  /**
    * @param script ScriptPubKey to add to PSBT
    * @param i index of inputMap to add script to
    * @return PSBT with added script
    */
  def addScriptToInput(script: ScriptPubKey, i: Int): PSBT = {
    require(
      i < inputMaps.size,
      s"i must be less than the number of input maps present in the psbt, $i >= ${inputMaps.size}")
    if (inputMaps(i).isFinalized) {
      this
    } else {
      var elements = inputMaps(i).elements

      val isWitScript = WitnessScriptPubKey.isWitnessScriptPubKey(script.asm)
      val redeemScript = inputMaps(i).getRecords[InputPSBTRecord.RedeemScript](
        PSBTInputKeyId.RedeemScriptKeyId)
      val hasWitScript = redeemScript.size == 1 && WitnessScriptPubKey
        .isWitnessScriptPubKey(redeemScript.head.redeemScript.asm)

      if (!isWitScript && hasWitScript) {
        elements = elements.filterNot(_.key.startsWith(hex"05")) :+ InputPSBTRecord
          .WitnessScript(script.asInstanceOf[RawScriptPubKey])
      } else {
        elements = elements.filterNot(_.key.startsWith(hex"04")) :+ InputPSBTRecord
          .RedeemScript(script)
      }

      val newMap = InputPSBTMap(elements).slimMap(transaction.inputs(i))
      val newInputMaps = inputMaps.patch(i, Seq(newMap), 1)

      PSBT(globalMap, newInputMaps, outputMaps)
    }
  }

  /**
    * @param script ScriptPubKey to add to PSBT
    * @param i index of outputMap to add script to
    * @return PSBT with added script
    */
  def addScriptToOutput(script: ScriptPubKey, i: Int): PSBT = {
    require(
      i < outputMaps.size,
      s"i must be less than the number of output maps present in the psbt, $i >= ${outputMaps.size}")
    if (isFinalized) {
      this
    } else {
      var elements = outputMaps(i).elements

      val isWitScript = WitnessScriptPubKey.isWitnessScriptPubKey(script.asm)
      val redeemScript =
        outputMaps(i).getRecords[psbt.OutputPSBTRecord.RedeemScript](
          PSBTOutputKeyId.RedeemScriptKeyId)
      val hasWitScript = redeemScript.size == 1 && WitnessScriptPubKey
        .isWitnessScriptPubKey(redeemScript.head.redeemScript.asm)

      if (!isWitScript && hasWitScript) {
        elements = elements.filterNot(_.key.startsWith(hex"01")) :+ OutputPSBTRecord
          .WitnessScript(script)
      } else {
        elements = elements.filterNot(_.key.startsWith(hex"00")) :+ OutputPSBTRecord
          .RedeemScript(script)
      }

      val newMap = OutputPSBTMap(elements)
      val newOutputMaps = outputMaps.patch(i, Seq(newMap), 1)

      PSBT(globalMap, inputMaps, newOutputMaps)
    }
  }

  /**
    * @param extKey ExtKey to derive key from
    * @param path path of key to add to PSBT
    * @param i index of inputMap to add the BIP32Path to
    * @return PSBT with added BIP32Path
    */
  def addKeyPathToInput(extKey: ExtKey, path: BIP32Path, i: Int): PSBT = {
    require(
      i < inputMaps.size,
      s"i must be less than the number of input maps present in the psbt, $i >= ${inputMaps.size}")
    if (inputMaps(i).isFinalized) {
      this
    } else {
      val map = inputMaps(i)
      var elements = map.elements

      val keyOpt = extKey.deriveChildPubKey(path)
      if (keyOpt.isSuccess && !elements.exists(
            _.key == hex"06" ++ keyOpt.get.bytes)) {
        val fp =
          if (extKey.fingerprint == hex"00000000")
            extKey.deriveChildPubKey(path.path.head).get.fingerprint
          else extKey.fingerprint
        elements = elements :+ InputPSBTRecord.BIP32DerivationPath(
          keyOpt.get.key,
          fp,
          path)
      }

      val newInputMaps = inputMaps.patch(i, Seq(InputPSBTMap(elements)), 1)
      PSBT(globalMap, newInputMaps, outputMaps)
    }
  }

  /**
    * @param extKey ExtKey to derive key from
    * @param path path of key to add to PSBT
    * @param i index of outputMap to add the BIP32Path to
    * @return PSBT with added BIP32Path
    */
  def addKeyPathToOutput(extKey: ExtKey, path: BIP32Path, i: Int): PSBT = {
    require(
      i < outputMaps.size,
      s"i must be less than the number of output maps present in the psbt, $i >= ${outputMaps.size}")
    if (isFinalized) {
      this
    } else {
      val map = outputMaps(i)
      var elements = map.elements

      val keyOpt = extKey.deriveChildPubKey(path)
      if (keyOpt.isSuccess && !elements.exists(
            _.key == hex"06" ++ keyOpt.get.bytes)) {
        val fp =
          if (extKey.fingerprint == hex"00000000")
            extKey.deriveChildPubKey(path.path.head).get.fingerprint
          else extKey.fingerprint
        elements = elements :+ OutputPSBTRecord.BIP32DerivationPath(
          keyOpt.get.key,
          fp,
          path)
      }

      val newOutputMaps = outputMaps.patch(i, Seq(OutputPSBTMap(elements)), 1)
      PSBT(globalMap, inputMaps, newOutputMaps)
    }
  }

  /**
    * @param hashType HashType to add to the input
    * @param i index of the input to add the HashType to
    * @return PSBT with added HashType
    */
  def addSigHashTypeToInput(hashType: HashType, i: Int): PSBT = {
    require(
      i < inputMaps.size,
      s"i must be less than the number of input maps present in the psbt, $i >= ${inputMaps.size}")
    if (inputMaps(i).isFinalized) {
      this
    } else {
      val newElements = inputMaps(i).elements
        .filterNot(_.key.startsWith(hex"03")) :+ SigHashType(hashType)

      val newInputMaps =
        inputMaps.patch(i, Seq(InputPSBTMap(newElements)), 1)
      PSBT(globalMap, newInputMaps, outputMaps)
    }
  }
}

object PSBT extends Factory[PSBT] {

  // The known version of PSBTs this library can process defined by https://github.com/bitcoin/bips/blob/master/bip-0174.mediawiki#version-numbers
  final val knownVersions: Vector[UInt32] = Vector(UInt32.zero)

  // The magic bytes and separator defined by https://github.com/bitcoin/bips/blob/master/bip-0174.mediawiki#specification
  final val magicBytes = hex"70736274ff"

  def fromBytes(bytes: ByteVector): PSBT = {
    require(bytes.startsWith(magicBytes),
            s"A PSBT must start with the magic bytes $magicBytes, got: $bytes")

    val globalBytes = bytes.drop(magicBytes.size)

    val global: GlobalPSBTMap = GlobalPSBTMap.fromBytes(globalBytes)

    val txRecords = global
      .getRecords[UnsignedTransaction](UnsignedTransactionKeyId)

    if (txRecords.isEmpty)
      throw new IllegalArgumentException("Invalid PSBT. No global transaction")
    if (txRecords.size > 1)
      throw new IllegalArgumentException(
        s"Invalid PSBT. There can only be one global transaction, got: ${txRecords.size}")

    val tx = txRecords.head.transaction
    val inputBytes = globalBytes.drop(global.bytes.size)

    @tailrec
    def inputLoop(
        bytes: ByteVector,
        numInputs: Int,
        accum: Seq[InputPSBTMap]): Vector[InputPSBTMap] = {
      if (numInputs <= 0 || bytes.isEmpty) {
        accum.toVector
      } else {
        val map = InputPSBTMap.fromBytes(bytes)
        inputLoop(bytes.drop(map.bytes.size), numInputs - 1, accum :+ map)
      }
    }

    val inputMaps = inputLoop(inputBytes, tx.inputs.size, Nil)

    val outputBytes =
      inputBytes.drop(inputMaps.foldLeft(0)(_ + _.bytes.size.toInt))
    @tailrec
    def outputLoop(
        bytes: ByteVector,
        numOutputs: Int,
        accum: Seq[OutputPSBTMap]): Vector[OutputPSBTMap] = {
      if (numOutputs <= 0 || bytes.isEmpty) {
        accum.toVector
      } else {
        val map = OutputPSBTMap.fromBytes(bytes)
        outputLoop(bytes.drop(map.bytes.size), numOutputs - 1, accum :+ map)
      }
    }

    val outputMaps = outputLoop(outputBytes, tx.outputs.size, Nil)

    val remainingBytes =
      outputBytes.drop(outputMaps.foldLeft(0)(_ + _.bytes.size.toInt))

    require(remainingBytes.isEmpty,
            s"The PSBT should be empty now, got: $remainingBytes")

    PSBT(global, inputMaps, outputMaps)
  }

  def fromBase64(base64: String): PSBT = {
    ByteVector.fromBase64(base64) match {
      case None =>
        throw new IllegalArgumentException(
          s"String given was not in base64 format, got: $base64")
      case Some(bytes) => fromBytes(bytes)
    }
  }
}

sealed trait PSBTRecord extends NetworkElement {

  def key: ByteVector

  def value: ByteVector

  def bytes: ByteVector = {
    val keySize = CompactSizeUInt.calc(key)
    val valueSize = CompactSizeUInt.calc(value)

    keySize.bytes ++ key ++ valueSize.bytes ++ value
  }
}

object PSBTRecord {

  def fromBytes(bytes: ByteVector): (ByteVector, ByteVector) = {
    val keyCmpctUInt = CompactSizeUInt.parse(bytes)

    if (keyCmpctUInt.toLong == 0) {
      (ByteVector.empty, ByteVector.empty)
    } else {
      val key = bytes.drop(keyCmpctUInt.size).take(keyCmpctUInt.toLong)
      val valueBytes = bytes.drop(keyCmpctUInt.size + keyCmpctUInt.toLong)
      val valueCmpctUInt = CompactSizeUInt.parse(valueBytes)
      val value = valueBytes
        .drop(valueCmpctUInt.size)
        .take(valueCmpctUInt.toLong)

      (key, value)
    }
  }
}

sealed trait GlobalPSBTRecord extends PSBTRecord

object GlobalPSBTRecord {
  case class UnsignedTransaction(transaction: Transaction)
      extends GlobalPSBTRecord {
    require(
      transaction.inputs.forall(_.scriptSignature == EmptyScriptSignature),
      "All ScriptSignatures must be empty")

    private val witnessIsEmpty = transaction match {
      case wtx: WitnessTransaction => wtx.witness.isInstanceOf[EmptyWitness]
      case _: BaseTransaction      => true
    }
    require(witnessIsEmpty, "Witness must be empty")

    override val key: ByteVector = hex"00"
    override val value: ByteVector = transaction.bytes
  }

  case class XPubKey(
      xpub: ExtPublicKey,
      masterFingerprint: ByteVector,
      derivationPath: BIP32Path)
      extends GlobalPSBTRecord {
    require(
      derivationPath.path.length == xpub.depth.toInt,
      s"Derivation path length does not match xpubkey depth, difference: ${derivationPath.path.length - xpub.depth.toInt}"
    )
    require(
      masterFingerprint.length == 4,
      s"Master key fingerprints are 4 bytes long, got: $masterFingerprint")

    override val key: ByteVector = hex"01" ++ xpub.bytes
    override val value: ByteVector = derivationPath.path
      .foldLeft(masterFingerprint)(_ ++ _.toUInt32.bytesLE)
  }

  case class Version(version: UInt32) extends GlobalPSBTRecord {
    override val key: ByteVector = hex"FB"
    override val value: ByteVector = version.bytesLE
  }

  case class Unknown(key: ByteVector, value: ByteVector)
      extends GlobalPSBTRecord

  def fromBytes(bytes: ByteVector): GlobalPSBTRecord = {
    val (key, value) = PSBTRecord.fromBytes(bytes)
    PSBTGlobalKeyId.fromByte(key.head) match {
      case UnsignedTransactionKeyId =>
        require(key.size == 1,
                s"The key must only contain the 1 byte type, got: ${key.size}")

        UnsignedTransaction(Transaction.fromBytes(value))
      case XPubKeyKeyId =>
        val xpub = ExtPublicKey.fromBytes(key.tail.take(78))
        val fingerprint = value.take(4)
        val path = BIP32Path.fromBytesLE(value.drop(4))
        XPubKey(xpub, fingerprint, path)
      case VersionKeyId =>
        require(key.size == 1,
                s"The key must only contain the 1 byte type, got: ${key.size}")

        val version = UInt32.fromBytesLE(value)

        require(PSBT.knownVersions.contains(version),
                s"Unknown version number given: $version")
        Version(version)
      case _ => GlobalPSBTRecord.Unknown(key, value)
    }
  }
}

sealed trait InputPSBTRecord extends PSBTRecord

object InputPSBTRecord {
  case class NonWitnessOrUnknownUTXO(transactionSpent: Transaction)
      extends InputPSBTRecord {
    override val key: ByteVector = hex"00"
    override val value: ByteVector = transactionSpent.bytes
  }

  case class WitnessUTXO(spentWitnessTransaction: TransactionOutput)
      extends InputPSBTRecord {
    override val key: ByteVector = hex"01"
    override val value: ByteVector = spentWitnessTransaction.bytes
  }

  case class PartialSignature(
      pubKey: ECPublicKey,
      signature: ECDigitalSignature)
      extends InputPSBTRecord {
    require(pubKey.size == 33, s"pubKey must be 33 bytes, got: ${pubKey.size}")

    override val key: ByteVector = hex"02" ++ pubKey.bytes
    override val value: ByteVector = signature.bytes
  }

  case class SigHashType(hashType: HashType) extends InputPSBTRecord {
    override val key: ByteVector = hex"03"
    override val value: ByteVector = hashType.num.bytesLE
  }

  case class RedeemScript(redeemScript: ScriptPubKey) extends InputPSBTRecord {
    override val key: ByteVector = hex"04"
    override val value: ByteVector = redeemScript.asmBytes
  }

  case class WitnessScript(witnessScript: RawScriptPubKey)
      extends InputPSBTRecord {
    override val key: ByteVector = hex"05"
    override val value: ByteVector = witnessScript.asmBytes
  }

  case class BIP32DerivationPath(
      pubKey: ECPublicKey,
      masterFingerprint: ByteVector,
      path: BIP32Path)
      extends InputPSBTRecord {
    require(pubKey.size == 33, s"pubKey must be 33 bytes, got: ${pubKey.size}")

    override val key: ByteVector = hex"06" ++ pubKey.bytes
    override val value: ByteVector =
      path.path.foldLeft(masterFingerprint)(_ ++ _.toUInt32.bytesLE)
  }

  case class FinalizedScriptSig(scriptSig: ScriptSignature, extra: ByteVector)
      extends InputPSBTRecord {
    override val key: ByteVector = hex"07"
    override val value: ByteVector = scriptSig.bytes ++ extra
  }

  case class FinalizedScriptWitness(scriptWitness: ScriptWitness)
      extends InputPSBTRecord {
    override val key: ByteVector = hex"08"
    override val value: ByteVector = scriptWitness.bytes
  }

  case class ProofOfReservesCommitment(porCommitment: ByteVector)
      extends InputPSBTRecord {
    override val key: ByteVector = hex"09"
    override val value: ByteVector = porCommitment
  }

  case class Unknown(key: ByteVector, value: ByteVector) extends InputPSBTRecord

  def fromBytes(bytes: ByteVector): InputPSBTRecord = {
    val (key, value) = PSBTRecord.fromBytes(bytes)
    PSBTInputKeyId.fromByte(key.head) match {
      case NonWitnessUTXOKeyId =>
        require(key.size == 1,
                s"The key must only contain the 1 byte type, got: ${key.size}")

        NonWitnessOrUnknownUTXO(Transaction(value))
      case WitnessUTXOKeyId =>
        require(key.size == 1,
                s"The key must only contain the 1 byte type, got: ${key.size}")

        WitnessUTXO(TransactionOutput.fromBytes(value))
      case PartialSignatureKeyId =>
        val pubKey = ECPublicKey(key.tail)
        val sig = ECDigitalSignature(value)
        PartialSignature(pubKey, sig)
      case SighashTypeKeyId =>
        require(key.size == 1,
                s"The key must only contain the 1 byte type, got: ${key.size}")

        SigHashType(HashType.fromBytesLE(value))
      case PSBTInputKeyId.RedeemScriptKeyId =>
        require(key.size == 1,
                s"The key must only contain the 1 byte type, got: ${key.size}")

        InputPSBTRecord.RedeemScript(ScriptPubKey.fromAsmBytes(value))
      case PSBTInputKeyId.WitnessScriptKeyId =>
        require(key.size == 1,
                s"The key must only contain the 1 byte type, got: ${key.size}")

        InputPSBTRecord.WitnessScript(RawScriptPubKey.fromAsmBytes(value))
      case PSBTInputKeyId.BIP32DerivationPathKeyId =>
        val pubKey = ECPublicKey(key.tail)
        val fingerprint = value.take(4)
        val path = BIP32Path.fromBytesLE(value.drop(4))
        InputPSBTRecord.BIP32DerivationPath(pubKey, fingerprint, path)
      case FinalizedScriptSigKeyId =>
        require(key.size == 1,
                s"The key must only contain the 1 byte type, got: ${key.size}")

        val sig = ScriptSignature(value)
        val extra = value.drop(sig.size)
        FinalizedScriptSig(sig, extra)
      case FinalizedScriptWitnessKeyId =>
        require(key.size == 1,
                s"The key must only contain the 1 byte type, got: ${key.size}")

        FinalizedScriptWitness(RawScriptWitnessParser.read(value))
      case ProofOfReservesCommitmentKeyId =>
        ProofOfReservesCommitment(value)
      case _ =>
        InputPSBTRecord.Unknown(key, value)
    }
  }
}

sealed trait OutputPSBTRecord extends PSBTRecord

object OutputPSBTRecord {

  case class RedeemScript(redeemScript: ScriptPubKey) extends OutputPSBTRecord {
    override val key: ByteVector = hex"00"
    override val value: ByteVector = redeemScript.asmBytes
  }

  case class WitnessScript(witnessScript: ScriptPubKey)
      extends OutputPSBTRecord {

    override val key: ByteVector = hex"01"
    override val value: ByteVector = witnessScript.asmBytes
  }

  case class BIP32DerivationPath(
      pubKey: ECPublicKey,
      masterFingerprint: ByteVector,
      path: BIP32Path)
      extends OutputPSBTRecord {
    require(pubKey.size == 33, s"pubKey must be 33 bytes, got: ${pubKey.size}")

    override val key: ByteVector = hex"02" ++ pubKey.bytes
    override val value: ByteVector =
      path.path.foldLeft(masterFingerprint)(_ ++ _.toUInt32.bytesLE)
  }

  case class Unknown(key: ByteVector, value: ByteVector)
      extends OutputPSBTRecord

  def fromBytes(bytes: ByteVector): OutputPSBTRecord = {
    val (key, value) = PSBTRecord.fromBytes(bytes)
    PSBTOutputKeyId.fromByte(key.head) match {
      case PSBTOutputKeyId.RedeemScriptKeyId =>
        require(key.size == 1,
                s"The key must only contain the 1 byte type, got: ${key.size}")

        OutputPSBTRecord.RedeemScript(ScriptPubKey.fromAsmBytes(value))
      case PSBTOutputKeyId.WitnessScriptKeyId =>
        require(key.size == 1,
                s"The key must only contain the 1 byte type, got: ${key.size}")

        OutputPSBTRecord.WitnessScript(ScriptPubKey.fromAsmBytes(value))
      case PSBTOutputKeyId.BIP32DerivationPathKeyId =>
        val pubKey = ECPublicKey(key.tail)
        val fingerprint = value.take(4)
        val path = BIP32Path.fromBytesLE(value.drop(4))

        OutputPSBTRecord.BIP32DerivationPath(pubKey, fingerprint, path)
      case _ =>
        OutputPSBTRecord.Unknown(key, value)
    }
  }
}

/**
  * A PSBTKeyId refers to the first byte of a key that signifies which kind of key-value map
  * is in a given PSBTRecord
  */
sealed trait PSBTKeyId {
  def byte: Byte
}

sealed trait PSBTGlobalKeyId extends PSBTKeyId

object PSBTGlobalKeyId {

  def fromByte(byte: Byte): PSBTGlobalKeyId = byte match {
    case UnsignedTransactionKeyId.byte => UnsignedTransactionKeyId
    case XPubKeyKeyId.byte             => XPubKeyKeyId
    case VersionKeyId.byte             => VersionKeyId
    case _: Byte                       => UnknownKeyId
  }

  final case object UnsignedTransactionKeyId extends PSBTGlobalKeyId {
    override val byte: Byte = 0x00.byteValue
  }

  final case object XPubKeyKeyId extends PSBTGlobalKeyId {
    override val byte: Byte = 0x01.byteValue
  }

  final case object VersionKeyId extends PSBTGlobalKeyId {
    override val byte: Byte = 0xFB.byteValue
  }

  final case object UnknownKeyId extends PSBTGlobalKeyId {
    override val byte: Byte = Byte.MaxValue
  }
}

sealed trait PSBTInputKeyId extends PSBTKeyId

object PSBTInputKeyId {

  def fromByte(byte: Byte): PSBTInputKeyId = byte match {
    case NonWitnessUTXOKeyId.byte            => NonWitnessUTXOKeyId
    case WitnessUTXOKeyId.byte               => WitnessUTXOKeyId
    case PartialSignatureKeyId.byte          => PartialSignatureKeyId
    case SighashTypeKeyId.byte               => SighashTypeKeyId
    case RedeemScriptKeyId.byte              => RedeemScriptKeyId
    case WitnessScriptKeyId.byte             => WitnessScriptKeyId
    case BIP32DerivationPathKeyId.byte       => BIP32DerivationPathKeyId
    case FinalizedScriptSigKeyId.byte        => FinalizedScriptSigKeyId
    case FinalizedScriptWitnessKeyId.byte    => FinalizedScriptWitnessKeyId
    case ProofOfReservesCommitmentKeyId.byte => ProofOfReservesCommitmentKeyId
    case _: Byte                             => UnknownKeyId

  }

  final case object NonWitnessUTXOKeyId extends PSBTInputKeyId {
    override val byte: Byte = 0x00.byteValue
  }

  final case object WitnessUTXOKeyId extends PSBTInputKeyId {
    override val byte: Byte = 0x01.byteValue
  }

  final case object PartialSignatureKeyId extends PSBTInputKeyId {
    override val byte: Byte = 0x02.byteValue
  }

  final case object SighashTypeKeyId extends PSBTInputKeyId {
    override val byte: Byte = 0x03.byteValue
  }

  final case object RedeemScriptKeyId extends PSBTInputKeyId {
    override val byte: Byte = 0x04.byteValue
  }

  final case object WitnessScriptKeyId extends PSBTInputKeyId {
    override val byte: Byte = 0x05.byteValue
  }

  final case object BIP32DerivationPathKeyId extends PSBTInputKeyId {
    override val byte: Byte = 0x06.byteValue
  }

  final case object FinalizedScriptSigKeyId extends PSBTInputKeyId {
    override val byte: Byte = 0x07.byteValue
  }

  final case object FinalizedScriptWitnessKeyId extends PSBTInputKeyId {
    override val byte: Byte = 0x08.byteValue
  }

  final case object ProofOfReservesCommitmentKeyId extends PSBTInputKeyId {
    override val byte: Byte = 0x09.byteValue
  }

  final case object UnknownKeyId extends PSBTInputKeyId {
    override val byte: Byte = Byte.MaxValue
  }
}

sealed trait PSBTOutputKeyId extends PSBTKeyId

object PSBTOutputKeyId {

  def fromByte(byte: Byte): PSBTOutputKeyId = byte match {
    case RedeemScriptKeyId.byte        => RedeemScriptKeyId
    case WitnessScriptKeyId.byte       => WitnessScriptKeyId
    case BIP32DerivationPathKeyId.byte => BIP32DerivationPathKeyId
    case _: Byte                       => UnknownKeyId
  }

  final case object RedeemScriptKeyId extends PSBTOutputKeyId {
    override val byte: Byte = 0x00.byteValue
  }

  final case object WitnessScriptKeyId extends PSBTOutputKeyId {
    override val byte: Byte = 0x01.byteValue
  }

  final case object BIP32DerivationPathKeyId extends PSBTOutputKeyId {
    override val byte: Byte = 0x02.byteValue
  }

  final case object UnknownKeyId extends PSBTOutputKeyId {
    override val byte: Byte = Byte.MaxValue
  }
}

sealed trait PSBTMap extends NetworkElement {
  require(elements.map(_.key).groupBy(identity).values.forall(_.length == 1),
          "All keys must be unique.")

  def elements: Vector[PSBTRecord]

  def bytes: ByteVector =
    elements
      .sortBy(_.key)
      .foldLeft(ByteVector.empty)(_ ++ _.bytes) ++ hex"00"
}

case class GlobalPSBTMap(elements: Vector[GlobalPSBTRecord]) extends PSBTMap {

  def getRecords[T <: GlobalPSBTRecord](key: PSBTGlobalKeyId): Vector[T] = {
    elements
      .filter(element => PSBTGlobalKeyId.fromByte(element.key.head) == key)
      .asInstanceOf[Vector[T]]
  }

  /**
    * Takes another GlobalPSBTMap and adds all records that are not contained in this GlobalPSBTMap
    * @param other GlobalPSBTMap to be combined with
    * @return A GlobalPSBTMap with the combined data of the two GlobalPSBTMaps
    */
  def combine(other: GlobalPSBTMap): GlobalPSBTMap = {
    GlobalPSBTMap((this.elements ++ other.elements).distinct)
  }
}

object GlobalPSBTMap {

  def fromBytes(bytes: ByteVector): GlobalPSBTMap = {
    @tailrec
    def loop(
        remainingBytes: ByteVector,
        accum: Seq[GlobalPSBTRecord]): Vector[GlobalPSBTRecord] = {
      if (remainingBytes.head == 0x00.byteValue) {
        accum.toVector
      } else {
        val record = GlobalPSBTRecord.fromBytes(remainingBytes)
        val next = remainingBytes.drop(record.bytes.size)

        loop(next, accum :+ record)
      }
    }
    GlobalPSBTMap(loop(bytes, Nil))
  }
}

case class InputPSBTMap(elements: Vector[InputPSBTRecord]) extends PSBTMap {

  def getRecords[T <: InputPSBTRecord](key: PSBTInputKeyId): Vector[T] = {
    elements
      .filter(element => PSBTInputKeyId.fromByte(element.key.head) == key)
      .asInstanceOf[Vector[T]]
  }

  def isFinalized: Boolean =
    getRecords(FinalizedScriptSigKeyId).nonEmpty || getRecords(
      FinalizedScriptWitnessKeyId).nonEmpty

  /**
    * Takes another InputPSBTMap and adds all records that are not contained in this InputPSBTMap
    * A record's distinctness is determined by its key
    * @param other InputPSBTMap to be combined with
    * @return A InputPSBTMap with the combined data of the two InputPSBTMaps
    */
  def combine(other: InputPSBTMap): InputPSBTMap = {
    InputPSBTMap((this.elements ++ other.elements).distinct)
  }

  /**
    * Takes the InputPSBTMap returns a UTXOSpendingInfo
    * that can be used to sign the input
    * @param txIn The transaction input that this InputPSBTMap represents
    * @param signers Signers that will be used to sign the input
    * @param conditionalPath Path that should be used for the script
    * @return A corresponding UTXOSpendingInfo
    */
  def toUTXOSpendingInfo(
      txIn: TransactionInput,
      signers: Seq[Sign],
      conditionalPath: ConditionalPath = ConditionalPath.NoConditionsLeft): UTXOSpendingInfo = {
    require(!isFinalized, s"Cannot update an InputPSBTMap that is finalized")

    val outPoint = txIn.previousOutput

    val witVec = getRecords[WitnessUTXO](WitnessUTXOKeyId)
    val txVec = getRecords[NonWitnessOrUnknownUTXO](NonWitnessUTXOKeyId)

    val output = if (witVec.size == 1) {
      witVec.head.spentWitnessTransaction
    } else if (txVec.size == 1) {
      val tx = txVec.head.transactionSpent
      tx.outputs(txIn.previousOutput.vout.toInt)
    } else {
      throw new UnsupportedOperationException(
        "Not enough information in the InputPSBTMap to get a valid UTXOSpendingInfo")
    }

    val redeemScriptVec = getRecords[RedeemScript](RedeemScriptKeyId)
    val redeemScriptOpt =
      if (redeemScriptVec.size == 1) Some(redeemScriptVec.head.redeemScript)
      else None

    val scriptWitnessVec = getRecords[WitnessScript](WitnessScriptKeyId)
    val scriptWitnessOpt =
      if (scriptWitnessVec.size == 1)
        Some(P2WSHWitnessV0(scriptWitnessVec.head.witnessScript))
      else None

    val hashTypeVec = getRecords[SigHashType](SighashTypeKeyId)
    val hashType =
      if (hashTypeVec.size == 1) hashTypeVec.head.hashType
      else HashType.sigHashAll

    BitcoinUTXOSpendingInfo(outPoint,
                            output,
                            signers,
                            redeemScriptOpt,
                            scriptWitnessOpt,
                            hashType,
                            conditionalPath)
  }

  def slimMap(txIn: TransactionInput): InputPSBTMap = {
    if (isFinalized) {
      this
    } else {
      var newElements = elements
      val nonWitUtxoVec =
        this.getRecords[NonWitnessOrUnknownUTXO](NonWitnessUTXOKeyId)
      if (nonWitUtxoVec.size == 1) {
        val witScriptVec =
          this.getRecords[InputPSBTRecord.WitnessScript](WitnessScriptKeyId)
        if (witScriptVec.size == 1) {
          val nonWitUtxo = nonWitUtxoVec.head.transactionSpent

          if (txIn.previousOutput.vout.toInt < nonWitUtxo.outputs.size) {
            val out = nonWitUtxo.outputs(txIn.previousOutput.vout.toInt)
            newElements = newElements.filter(!_.key.startsWith(hex"01")) :+ WitnessUTXO(
              out)
          }
          newElements = newElements.filter(!_.key.startsWith(hex"00"))
        }
      }
      InputPSBTMap(newElements)
    }
  }
}

object InputPSBTMap {

  def fromBytes(bytes: ByteVector): InputPSBTMap = {
    @tailrec
    def loop(
        remainingBytes: ByteVector,
        accum: Seq[InputPSBTRecord]): Vector[InputPSBTRecord] = {
      if (remainingBytes.head == 0x00.byteValue) {
        accum.toVector
      } else {
        val record = InputPSBTRecord.fromBytes(remainingBytes)
        val next = remainingBytes.drop(record.bytes.size)

        loop(next, accum :+ record)
      }
    }

    InputPSBTMap(loop(bytes, Nil))
  }
}
case class OutputPSBTMap(elements: Vector[OutputPSBTRecord]) extends PSBTMap {

  def getRecords[T <: OutputPSBTRecord](key: PSBTOutputKeyId): Vector[T] = {
    elements
      .filter(element => PSBTOutputKeyId.fromByte(element.key.head) == key)
      .asInstanceOf[Vector[T]]
  }

  /**
    * Takes another OutputPSBTMap and adds all records that are not contained in this OutputPSBTMap
    * A record's distinctness is determined by its key
    * @param other OutputPSBTMap to be combined with
    * @return A OutputPSBTMap with the combined data of the two OutputPSBTMaps
    */
  def combine(other: OutputPSBTMap): OutputPSBTMap = {
    OutputPSBTMap((this.elements ++ other.elements).distinct)
  }
}

object OutputPSBTMap {

  def fromBytes(bytes: ByteVector): OutputPSBTMap = {
    @tailrec
    def loop(
        remainingBytes: ByteVector,
        accum: Seq[OutputPSBTRecord]): Vector[OutputPSBTRecord] = {
      if (remainingBytes.head == 0x00.byteValue) {
        accum.toVector
      } else {
        val record = OutputPSBTRecord.fromBytes(remainingBytes)
        val next = remainingBytes.drop(record.bytes.size)

        loop(next, accum :+ record)
      }
    }

    OutputPSBTMap(loop(bytes, Nil))
  }
}