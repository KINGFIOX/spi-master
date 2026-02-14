//////////////////////////////////////////////////////////////////////
////                                                              ////
////  spi_top.v                                                   ////
////                                                              ////
////  This file is part of the SPI IP core project                ////
////  http://www.opencores.org/projects/spi/                      ////
////                                                              ////
////  Author(s):                                                  ////
////      - Simon Srot (simons@opencores.org)                     ////
////                                                              ////
////  All additional information is avaliable in the Readme.txt   ////
////  file.                                                       ////
////                                                              ////
//////////////////////////////////////////////////////////////////////
////                                                              ////
//// Copyright (C) 2002 Authors                                   ////
////                                                              ////
//// This source file may be used and distributed without         ////
//// restriction provided that this copyright statement is not    ////
//// removed from the file and that any derivative work contains  ////
//// the original copyright notice and the associated disclaimer. ////
////                                                              ////
//// This source file is free software; you can redistribute it   ////
//// and/or modify it under the terms of the GNU Lesser General   ////
//// Public License as published by the Free Software Foundation; ////
//// either version 2.1 of the License, or (at your option) any   ////
//// later version.                                               ////
////                                                              ////
//// This source is distributed in the hope that it will be       ////
//// useful, but WITHOUT ANY WARRANTY; without even the implied   ////
//// warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR      ////
//// PURPOSE.  See the GNU Lesser General Public License for more ////
//// details.                                                     ////
////                                                              ////
//// You should have received a copy of the GNU Lesser General    ////
//// Public License along with this source; if not, download it   ////
//// from http://www.opencores.org/lgpl.shtml                     ////
////                                                              ////
//////////////////////////////////////////////////////////////////////


`include "spi_defines.v"

module spi_top (
    // APB signals
    input  wire        pclk,
    input  wire        presetn,    // active-low reset (AMBA standard)
    input  wire  [4:0] paddr,
    input  wire        psel,
    input  wire        penable,
    input  wire        pwrite,
    input  wire  [3:0] pstrb,
    input  wire [31:0] pwdata,
    output wire [31:0] prdata,
    output wire        pready,
    output wire        pslverr,
    output reg         int_o,

    // SPI signals
    output [`SPI_SS_NB-1:0] ss_pad_o,
    output sclk_pad_o,
    output mosi_pad_o,
    input  miso_pad_i
);

  // Internal active-high reset (sub-modules use posedge rst)
  wire rst = ~presetn;

  // mmio register
  reg  [  `SPI_DIVIDER_LEN-1:0] divider; // Divider register
  reg  [  `SPI_CTRL_BIT_NB-1:0] ctrl; // Control and status register
  reg  [        `SPI_SS_NB-1:0] ss; // Slave select register
  wire [     `SPI_MAX_CHAR-1:0] rx; // Rx register

  // Internal signals
  wire tip; // transfer in progress
  wire pos_edge;
  wire neg_edge;
  wire last_bit;

  // ─── APB bus control ─────────────────────────────────────
  wire reg_write = psel & penable & pwrite;   // write in access phase

  // No wait states, no errors
  assign pready  = 1'b1;
  assign pslverr = 1'b0;

  // ─── Address decoder ─────────────────────────────────────
  wire spi_divider_sel = psel & (paddr[`SPI_OFS_BITS] == `SPI_DEVIDE);
  wire spi_ctrl_sel    = psel & (paddr[`SPI_OFS_BITS] == `SPI_CTRL);
  wire spi_ss_sel      = psel & (paddr[`SPI_OFS_BITS] == `SPI_SS);
  wire [3:0] spi_tx_sel;
  assign spi_tx_sel[0]   = psel & (paddr[`SPI_OFS_BITS] == `SPI_TX_0);
  assign spi_tx_sel[1]   = psel & (paddr[`SPI_OFS_BITS] == `SPI_TX_1);
  assign spi_tx_sel[2]   = psel & (paddr[`SPI_OFS_BITS] == `SPI_TX_2);
  assign spi_tx_sel[3]   = psel & (paddr[`SPI_OFS_BITS] == `SPI_TX_3);

  // ─── Read from registers (combinational) ─────────────────
  reg [31:0] prdata_mux; // just amused the compiler
  always @(*) begin
    case (paddr[`SPI_OFS_BITS])
      `SPI_RX_0:   prdata_mux = rx[31:0];
      `SPI_RX_1:   prdata_mux = rx[63:32];
      `SPI_RX_2:   prdata_mux = rx[95:64];
      `SPI_RX_3:   prdata_mux = rx[`SPI_MAX_CHAR-1:96];
      `SPI_CTRL:   prdata_mux = {{32 - `SPI_CTRL_BIT_NB{1'b0}}, ctrl};
      `SPI_DEVIDE: prdata_mux = {{32 - `SPI_DIVIDER_LEN{1'b0}}, divider};
      `SPI_SS:     prdata_mux = {{32 - `SPI_SS_NB{1'b0}}, ss};
      default:     prdata_mux = 32'b0;
    endcase
  end
  assign prdata = prdata_mux;

  // ─── Interrupt ───────────────────────────────────────────
  always @(posedge pclk or negedge presetn) begin
    if (!presetn) int_o <= 1'b0;
    else if (ie && tip && last_bit && pos_edge) int_o <= 1'b1;
    else if (psel && penable) int_o <= 1'b0; // reset interrupt flag
  end

  // ─── Divider register (16-bit, byte-lane write) ──────────
  always @(posedge pclk or negedge presetn) begin
    if (!presetn) divider <= {`SPI_DIVIDER_LEN{1'b1}};
    else if (reg_write && spi_divider_sel && !tip) begin
      if (pstrb[0]) divider[ 7:0] <= pwdata[ 7:0];
      if (pstrb[1]) divider[15:8] <= pwdata[15:8];
    end
  end

  // ─── Ctrl register ──────────────────────────────────────
  always @(posedge pclk or negedge presetn) begin
    if (!presetn) ctrl <= {`SPI_CTRL_BIT_NB{1'b0}};
    else if (reg_write && spi_ctrl_sel && !tip) begin
      if (pstrb[0]) ctrl[7:0] <= pwdata[7:0] | {7'b0, ctrl[0]};
      if (pstrb[1]) ctrl[`SPI_CTRL_BIT_NB-1:8] <= pwdata[`SPI_CTRL_BIT_NB-1:8];
    end else if (tip && last_bit && pos_edge) ctrl[`SPI_CTRL_GO] <= 1'b0;
  end

  wire [`SPI_CHAR_LEN_BITS-1:0] char_len = ctrl[`SPI_CTRL_CHAR_LEN];
  wire rx_negedge = ctrl[`SPI_CTRL_RX_NEGEDGE];
  wire tx_negedge = ctrl[`SPI_CTRL_TX_NEGEDGE];
  wire go         = ctrl[`SPI_CTRL_GO];
  wire lsb        = ctrl[`SPI_CTRL_LSB];
  wire ie         = ctrl[`SPI_CTRL_IE];
  wire ass        = ctrl[`SPI_CTRL_ASS];

  // ─── Slave select register (8-bit) ──────────────────────
  always @(posedge pclk or negedge presetn) begin
    if (!presetn) ss <= {`SPI_SS_NB{1'b0}};
    else if (reg_write && spi_ss_sel && !tip) begin
      if (pstrb[0]) ss <= pwdata[`SPI_SS_NB-1:0];
    end
  end

  assign ss_pad_o = ~((ss & {`SPI_SS_NB{tip & ass}}) | (ss & {`SPI_SS_NB{!ass}}));

  // ─── Sub-modules ─────────────────────────────────────────
  spi_clgen clgen (
      .clk_in(pclk),
      .rst(rst),
      .go(go),
      .enable(tip),
      .last_clk(last_bit),
      .divider(divider),
      .clk_out(sclk_pad_o),
      .pos_edge(pos_edge),
      .neg_edge(neg_edge)
  );

  spi_shift shift (
      .clk(pclk),
      .rst(rst),
      .len(char_len[`SPI_CHAR_LEN_BITS-1:0]),
      .latch(spi_tx_sel[3:0] & {4{penable & pwrite}}),
      .byte_sel(pstrb),
      .lsb(lsb),
      .go(go),
      .pos_edge(pos_edge),
      .neg_edge(neg_edge),
      .rx_negedge(rx_negedge),
      .tx_negedge(tx_negedge),
      .tip(tip),
      .last(last_bit),
      .p_in(pwdata),
      .p_out(rx),
      .s_clk(sclk_pad_o),
      .s_in(miso_pad_i),
      .s_out(mosi_pad_o)
  );
endmodule
