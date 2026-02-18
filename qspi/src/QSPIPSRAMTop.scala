package org.chipsalliance.qspi

import chisel3._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule

class QSPIPSRAMInterface(parameter: QSPIParameter) extends Bundle {
  val clock   = Input(Clock())
  val reset   = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())

  // APB slave interface
  val paddr   = Input(UInt(32.W))
  val psel    = Input(Bool())
  val penable = Input(Bool())
  val pwrite  = Input(Bool())
  val pstrb   = Input(UInt(4.W))
  val pwdata  = Input(UInt(32.W))
  val prdata  = Output(UInt(32.W))
  val pready  = Output(Bool())
  val pslverr = Output(Bool())
  val intO    = Output(Bool())

  // Debug outputs
  val qspi_sck  = Output(Bool())
  val qspi_ce_n = Output(Bool())
}

@instantiable
class QSPIPSRAMTop(val parameter: QSPIParameter)
    extends FixedIORawModule(new QSPIPSRAMInterface(parameter))
    with SerializableModule[QSPIParameter]
    with ImplicitClock
    with ImplicitReset {
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  val qspiMaster = Module(new QSPI(parameter))
  val psramDev   = Module(new psram)

  // Clock and reset
  qspiMaster.io.clock := io.clock
  qspiMaster.io.reset := io.reset

  // APB connections
  qspiMaster.io.apb.paddr   := io.paddr
  qspiMaster.io.apb.psel    := io.psel
  qspiMaster.io.apb.penable := io.penable
  qspiMaster.io.apb.pwrite  := io.pwrite
  qspiMaster.io.apb.pstrb   := io.pstrb
  qspiMaster.io.apb.pwdata  := io.pwdata
  io.prdata                 := qspiMaster.io.apb.prdata
  io.pready                 := qspiMaster.io.apb.pready
  io.pslverr                := qspiMaster.io.apb.pslverr
  io.intO                   := qspiMaster.io.intO

  // QSPI master <-> PSRAM slave
  psramDev.io.sck  := qspiMaster.io.qspiio.sck
  psramDev.io.ce_n := qspiMaster.io.qspiio.ce_n
  psramDev.io.dio  <> qspiMaster.io.qspiio.dio
  psramDev.systemReset := io.reset.asAsyncReset

  // Debug outputs
  io.qspi_sck  := qspiMaster.io.qspiio.sck
  io.qspi_ce_n := qspiMaster.io.qspiio.ce_n
}
