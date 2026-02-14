//////////////////////////////////////////////////////////////////////
////                                                              ////
////  spi_define.v                                                ////
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

`define SPI_DIVIDER_LEN 16

`define SPI_MAX_CHAR 128
`define SPI_CHAR_LEN_BITS 7

`define SPI_SS_NB 8

`define SPI_OFS_BITS 4:2

//
// Register offset
//
`define SPI_RX_0 0
`define SPI_RX_1 1
`define SPI_RX_2 2
`define SPI_RX_3 3
`define SPI_TX_0 0
`define SPI_TX_1 1
`define SPI_TX_2 2
`define SPI_TX_3 3
`define SPI_CTRL 4
`define SPI_DEVIDE 5
`define SPI_SS 6

//
// Number of bits in ctrl register
//
`define SPI_CTRL_BIT_NB 14

//
// Control register bit position
//
`define SPI_CTRL_ASS 13
`define SPI_CTRL_IE 12
`define SPI_CTRL_LSB 11
`define SPI_CTRL_TX_NEGEDGE 10
`define SPI_CTRL_RX_NEGEDGE 9
`define SPI_CTRL_GO 8
`define SPI_CTRL_RES_1 7
`define SPI_CTRL_CHAR_LEN 6:0

