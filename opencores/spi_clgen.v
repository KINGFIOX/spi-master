//////////////////////////////////////////////////////////////////////
////                                                              ////
////  spi_clgen.v                                                 ////
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

module spi_clgen (
  input clk_in,
  input rst,
  input go,
  input enable,
  input last_clk,
  input [`SPI_DIVIDER_LEN-1:0] divider,
  output reg clk_out,
  output reg pos_edge,
  output reg neg_edge
);

  reg [`SPI_DIVIDER_LEN-1:0] cnt;  // clock counter
  wire cnt_zero = (cnt == 0);
  wire cnt_one  = (cnt == 1);

  // Counter counts half period
  always @(posedge clk_in or posedge rst) begin
    if (rst) cnt <= {`SPI_DIVIDER_LEN{1'b1}};  // 全 1 (最大值)
    else begin
      if (!enable || cnt_zero) cnt <= divider; // reset counter
      else cnt <= cnt - 1'b1;
    end
  end

  // clk_out is asserted every other half period
  always @(posedge clk_in or posedge rst) begin
    if (rst) clk_out <= 1'b0;
    else clk_out <= (enable && cnt_zero && (!last_clk || clk_out)) ? ~clk_out : clk_out;
  end

  // Pos and neg edge signals
  always @(posedge clk_in or posedge rst) begin
    if (rst) begin
      pos_edge <= 1'b0;
      neg_edge <= 1'b0;
    end else begin
      pos_edge  <= (enable && !clk_out && cnt_one)
        || (!(|divider) && clk_out)
        || (!(|divider) && go && !enable);
      neg_edge <= (enable && clk_out && cnt_one)
        || (!(|divider) && !clk_out && enable);
    end
  end
endmodule

