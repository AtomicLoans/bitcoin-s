package org.bitcoins.core.wallet.utxo

/** Represents the various states a transaction output can be in */
sealed abstract class TxoState

sealed abstract class ReceivedState extends TxoState

sealed abstract class SpentState extends TxoState

object TxoState {

  /** Means that no funds have been sent to this utxo EVER */
  final case object DoesNotExist extends TxoState

  /** Means we have received funds to this utxo, but they are not confirmed */
  final case object PendingConfirmationsReceived extends ReceivedState

  /** Means we have received funds and they are fully confirmed for this utxo */
  final case object ConfirmedReceived extends ReceivedState

  /** Means we have not spent this utxo yet, but will be used in a future transaction */
  final case object Reserved extends SpentState

  /** Means we have spent this utxo, but it is not fully confirmed */
  final case object PendingConfirmationsSpent extends SpentState

  /** Means we have spent this utxo, and it is fully confirmed */
  final case object ConfirmedSpent extends SpentState

  val pendingConfStates: Set[TxoState] =
    Set(TxoState.PendingConfirmationsReceived,
        TxoState.PendingConfirmationsSpent)

  val confirmedStates: Set[TxoState] =
    Set(TxoState.ConfirmedReceived, TxoState.ConfirmedSpent)

  val receivedStates: Set[TxoState] =
    Set(PendingConfirmationsReceived, ConfirmedReceived)

  val spentStates: Set[TxoState] =
    Set(PendingConfirmationsSpent, TxoState.ConfirmedSpent, Reserved)

  val all: Vector[TxoState] = Vector(DoesNotExist,
                                     PendingConfirmationsReceived,
                                     ConfirmedReceived,
                                     Reserved,
                                     PendingConfirmationsSpent,
                                     ConfirmedSpent)

  def fromString(str: String): Option[TxoState] = {
    all.find(state => str.toLowerCase() == state.toString.toLowerCase)
  }
}
