package org.chipsalliance.qspi

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

/** APB slave interface bundle (32-bit address for memory-mapped QSPI flash). */
class APBSlaveIO extends Bundle {
  val paddr   = Input(UInt(32.W))
  val psel    = Input(Bool())
  val penable = Input(Bool())
  val pwrite  = Input(Bool())
  val pstrb   = Input(UInt(4.W))
  val pwdata  = Input(UInt(32.W))
  val prdata  = Output(UInt(32.W))
  val pready  = Output(Bool())
  val pslverr = Output(Bool())
}

class TriStateInBuf(bits: Int) extends BlackBox(Map("width" -> bits)) with HasBlackBoxInline {
  val io = IO(new Bundle {
    val dio = Analog(bits.W)
    val dout = Input(UInt(bits.W))
    val out_en = Input(Bool())
    val din = Output(UInt(bits.W))
  })

  setInline("TriStateInBuf.sv",
    """module TriStateInBuf #(
      |  parameter width = 1
      |)(
      |    inout  [width-1:0] dio,
      |    input  [width-1:0] dout,
      |    input              out_en,
      |    output [width-1:0] din
      |);
      |  assign din = dio;
      |  assign dio = out_en ? dout : {width{1'bz}};
      |endmodule
    """.stripMargin)
}

object TriStateInBuf {
  def apply(dio: Analog, dout: UInt, out_en: Bool) = {
    val buf = Module(new TriStateInBuf(dio.getWidth))
    buf.io.dio <> dio
    buf.io.dout := dout
    buf.io.out_en := out_en
    buf.io.din
  }
}