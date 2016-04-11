package org.scalacoin.script.interpreter

import org.scalacoin.protocol.script._
import org.scalacoin.protocol.transaction.Transaction
import org.scalacoin.script.flag.{ScriptVerifyP2SH, ScriptVerifyCleanStack, ScriptVerifyCheckLocktimeVerify}
import org.scalacoin.script.locktime.{OP_CHECKLOCKTIMEVERIFY, LockTimeInterpreter}
import org.scalacoin.script.splice._
import org.scalacoin.script.{ScriptProgramFactory, ScriptProgram}
import org.scalacoin.script.arithmetic._
import org.scalacoin.script.bitwise._
import org.scalacoin.script.constant._
import org.scalacoin.script.control._
import org.scalacoin.script.crypto._
import org.scalacoin.script.reserved._
import org.scalacoin.script.stack._
import org.scalacoin.util.{BitcoinScriptUtil, BitcoinSLogger}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec

/**
 * Created by chris on 1/6/16.
 */
trait ScriptInterpreter extends CryptoInterpreter with StackInterpreter with ControlOperationsInterpreter
  with BitwiseInterpreter with ConstantInterpreter with ArithmeticInterpreter with SpliceInterpreter
  with LockTimeInterpreter with BitcoinSLogger {



  /**
   * Runs an entire script though our script programming language and
   * returns true or false depending on if the script was valid
   * @param program the program to be interpreted
   * @return
   */
  def run(program : ScriptProgram) : Boolean = {
    /**
     *
     * @param program
     * @return boolean this boolean represents if the program hit any invalid states within the execution
     *         this does NOT indicate if the final value of the stack is true/false
     * @return program the final state of the program after being evaluated by the interpreter
     */
    @tailrec
    def loop(program : ScriptProgram) : (Boolean,ScriptProgram) = {
      logger.debug("Stack: " + program.stack)
      logger.debug("Script: " + program.script)
      program.script match {
        //if at any time we see that the program is not valid
        //cease script execution
        case _ if !program.isValid =>
          logger.error("Script program was marked as invalid: " + program)
          (false,ScriptProgramFactory.factory(program,false))
        case _ if !program.script.intersect(Seq(OP_VERIF,OP_VERNOTIF)).isEmpty =>
          logger.error("Script is invalid even when a OP_VERIF or OP_VERNOTIF occurs in an unexecuted OP_IF branch")
          (false,ScriptProgramFactory.factory(program,false))
        //disabled splice operation
        case _ if !program.script.intersect(Seq(OP_CAT,OP_SUBSTR,OP_LEFT,OP_RIGHT)).isEmpty =>
          logger.error("Script is invalid because it contains a disabled splice operation")
          (false,ScriptProgramFactory.factory(program,false))
        //disabled bitwise operations
        case _ if !program.script.intersect(Seq(OP_INVERT, OP_AND, OP_OR, OP_XOR)).isEmpty =>
          logger.error("Script is invalid because it contains a disabled bitwise operation")
          (false,ScriptProgramFactory.factory(program,false))
        //disabled arithmetic operations
        case _ if !program.script.intersect(Seq(OP_MUL, OP_2MUL, OP_DIV, OP_2DIV, OP_MOD, OP_LSHIFT, OP_RSHIFT)).isEmpty =>
          logger.error("Script is invalid because it contains a disabled arithmetic operation")
          (false,ScriptProgramFactory.factory(program,false))
        //stack operations
        case OP_DUP :: t => loop(opDup(program))
        case OP_DEPTH :: t => loop(opDepth(program))
        case OP_TOALTSTACK :: t => loop(opToAltStack(program))
        case OP_FROMALTSTACK :: t => loop(opFromAltStack(program))
        case OP_DROP :: t => loop(opDrop(program))
        case OP_IFDUP :: t => loop(opIfDup(program))
        case OP_NIP :: t => loop(opNip(program))
        case OP_OVER :: t => loop(opOver(program))
        case OP_PICK :: t => loop(opPick(program))
        case OP_ROLL :: t => loop(opRoll(program))
        case OP_ROT :: t => loop(opRot(program))
        case OP_2ROT :: t => loop(op2Rot(program))
        case OP_2DROP :: t => loop(op2Drop(program))
        case OP_SWAP :: t => loop(opSwap(program))
        case OP_TUCK :: t => loop(opTuck(program))
        case OP_2DUP :: t => loop(op2Dup(program))
        case OP_3DUP :: t => loop(op3Dup(program))
        case OP_2OVER :: t => loop(op2Over(program))
        case OP_2SWAP :: t => loop(op2Swap(program))

        //arithmetic operations
        case OP_ADD :: t => loop(opAdd(program))
        case OP_1ADD :: t => loop(op1Add(program))
        case OP_1SUB :: t => loop(op1Sub(program))
        case OP_SUB :: t => loop(opSub(program))
        case OP_ABS :: t => loop(opAbs(program))
        case OP_NEGATE :: t => loop(opNegate(program))
        case OP_NOT :: t => loop(opNot(program))
        case OP_0NOTEQUAL :: t => loop(op0NotEqual(program))
        case OP_BOOLAND :: t => loop(opBoolAnd(program))
        case OP_BOOLOR :: t => loop(opBoolOr(program))
        case OP_NUMEQUAL :: t => loop(opNumEqual(program))
        case OP_NUMEQUALVERIFY :: t => loop(opNumEqualVerify(program))
        case OP_NUMNOTEQUAL :: t => loop(opNumNotEqual(program))
        case OP_LESSTHAN :: t => loop(opLessThan(program))
        case OP_GREATERTHAN :: t => loop(opGreaterThan(program))
        case OP_LESSTHANOREQUAL :: t => loop(opLessThanOrEqual(program))
        case OP_GREATERTHANOREQUAL :: t => loop(opGreaterThanOrEqual(program))
        case OP_MIN :: t => loop(opMin(program))
        case OP_MAX :: t => loop(opMax(program))
        case OP_WITHIN :: t => loop(opWithin(program))

        //bitwise operations
        case OP_EQUAL :: t =>
          val newProgram = opEqual(program)
          loop(newProgram)

        case OP_EQUALVERIFY :: t => loop(opEqualVerify(program))

        case (scriptNumberOp : ScriptNumberOperation) :: t =>
          if (scriptNumberOp == OP_0) loop(ScriptProgramFactory.factory(program,OP_0 :: program.stack, t))
          else loop(ScriptProgramFactory.factory(program, scriptNumberOp.scriptNumber :: program.stack, t))
        case (bytesToPushOntoStack : BytesToPushOntoStack) :: t => loop(pushScriptNumberBytesToStack(program))
        case (scriptNumber : ScriptNumber) :: t =>
          loop(ScriptProgramFactory.factory(program, scriptNumber :: program.stack, t))
        case OP_PUSHDATA1 :: t => loop(opPushData1(program))
        case OP_PUSHDATA2 :: t => loop(opPushData2(program))
        case OP_PUSHDATA4 :: t => loop(opPushData4(program))

        case ScriptConstantImpl(x) :: t => loop(ScriptProgramFactory.factory(program, ScriptConstantImpl(x) :: program.stack, t))

        //control operations
        case OP_IF :: t => loop(opIf(program))
        case OP_NOTIF :: t => loop(opNotIf(program))
        case OP_ELSE :: t => loop(opElse(program))
        case OP_ENDIF :: t => loop(opEndIf(program))
        case OP_RETURN :: t =>
          val newProgram = opReturn(program)
          (newProgram.isValid, newProgram)
        case OP_VERIFY :: t => loop(opVerify(program))

        //crypto operations
        case OP_HASH160 :: t => loop(opHash160(program))
        case OP_CHECKSIG :: t => loop(opCheckSig(program))
        case OP_SHA1 :: t => loop(opSha1(program))
        case OP_RIPEMD160 :: t => loop(opRipeMd160(program))
        case OP_SHA256 :: t => loop(opSha256(program))
        case OP_HASH256 :: t => loop(opHash256(program))
        case OP_CODESEPARATOR :: t => loop(opCodeSeparator(program))
        case OP_CHECKMULTISIG :: t => loop(opCheckMultiSig(program))
        case OP_CHECKMULTISIGVERIFY :: t => loop(opCheckMultiSigVerify(program))
        //reserved operations
        case (nop : NOP) :: t => loop(ScriptProgramFactory.factory(program,program.stack,t))
        case OP_RESERVED :: t =>
          logger.error("OP_RESERVED automatically marks transaction invalid")
          (false,program)
        case OP_VER :: t =>
          logger.error("Transaction is invalid when executing OP_VER")
          (false,program)
        case OP_RESERVED1 :: t =>
          logger.error("Transaction is invalid when executing OP_RESERVED1")
          (false,program)
        case OP_RESERVED2 :: t =>
          logger.error("Transaction is invalid when executing OP_RESERVED2")
          (false,program)
        //splice operations
        case OP_SIZE :: t => loop(opSize(program))
        //locktime operations
        case OP_CHECKLOCKTIMEVERIFY :: t =>
          //check if CLTV is enforced yet
          if (program.flags.contains(ScriptVerifyCheckLocktimeVerify)) loop(opCheckLockTimeVerify(program))
          //treat this as OP_NOP2 since CLTV is not enforced yet
          //in this case, just remove OP_CLTV from the stack and continue
          else loop(ScriptProgramFactory.factory(program, program.script.tail, ScriptProgramFactory.Script))
        //no more script operations to run, return whether the program is valid and the final state of the program
        case Nil => (program.isValid, program)

        case h :: t => throw new RuntimeException(h + " was unmatched")
      }
    }

    val scriptSigProgram = ScriptProgramFactory.factory(program,Seq(),program.txSignatureComponent.scriptSignature.asm)

    val (result,executedProgram) = program.txSignatureComponent.scriptSignature match {
      //if the P2SH script flag is not set, we evaluate a p2sh scriptSig just like any other scriptSig
      case scriptSig : P2SHScriptSignature if (program.flags.contains(ScriptVerifyP2SH)) =>

        //first run the serialized redeemScript && the p2shScriptPubKey to see if the hashes match
        val hashCheckProgram = ScriptProgramFactory.factory(program, Seq(scriptSig.asm.last), program.txSignatureComponent.scriptPubKey.asm)
        val (hashesMatch, hashesMatchProgram) = loop(hashCheckProgram)
        hashesMatch match {
          case true =>
            logger.info("Hashes matched between the p2shScriptSignature & the p2shScriptPubKey")
            //we need to run the deserialized redeemScript & the scriptSignature without the serialized redeemScript
            val stack = BitcoinScriptUtil.filterPushOps(scriptSig.scriptSignatureNoRedeemScript.asm.reverse)
            logger.debug("P2sh stack: " + stack)
            logger.debug("P2sh redeemScript: " + scriptSig.redeemScript.asm)
            val p2shRedeemScriptProgram = ScriptProgramFactory.factory(hashesMatchProgram,stack, scriptSig.redeemScript.asm)
            loop(p2shRedeemScriptProgram)
          case false =>
            logger.warn("P2SH scriptPubKey hash did not match the hash for the serialized redeemScript")
            (hashesMatch,hashesMatchProgram)
        }
      case _ : P2PKHScriptSignature | _ : P2PKScriptSignature | _ : MultiSignatureScriptSignature |
           _ : NonStandardScriptSignature | _ : P2SHScriptSignature | EmptyScriptSignature =>

        val (scriptSigProgramIsValid,scriptSigExecutedProgram) = loop(scriptSigProgram)
        logger.debug("program.txSignatureComponent.scriptSignature.asm: " + scriptSigExecutedProgram.txSignatureComponent.scriptSignature.asm)
        logger.debug("stack after scriptSig execution: " + scriptSigExecutedProgram.stack)
        logger.debug("script pubkey to be executed: " + scriptSigExecutedProgram.txSignatureComponent.scriptPubKey)
        if (scriptSigProgramIsValid) {
          logger.debug("We do not check a redeemScript against a non p2sh scriptSig")
          //now run the scriptPubKey script through the interpreter with the scriptSig as the stack arguments
          val scriptPubKeyProgram = ScriptProgramFactory.factory(scriptSigExecutedProgram.txSignatureComponent,
            scriptSigExecutedProgram.stack,scriptSigExecutedProgram.txSignatureComponent.scriptPubKey.asm)
          val (scriptPubKeyProgramIsValid, scriptPubKeyExecutedProgram) = loop(scriptPubKeyProgram)
          //if the program is valid, return if the stack top is true
          //else the program is false since something illegal happened during script evaluation
          scriptPubKeyProgramIsValid match {
            case true =>  (scriptPubKeyExecutedProgram.stackTopIsTrue,scriptPubKeyExecutedProgram)
            case false => (false, scriptPubKeyExecutedProgram)
          }

        } else (scriptSigProgramIsValid,scriptSigExecutedProgram)

    }

    if (result && executedProgram.flags.contains(ScriptVerifyCleanStack)) {
      //require that the stack after execution has exactly one element on it
      result && executedProgram.stack.size == 1
    } else result

  }

}

object ScriptInterpreter extends ScriptInterpreter