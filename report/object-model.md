# Chisel Object Model (OM) 机制说明

## 什么是 Object Model

Object Model（简称 OM）是 Chisel/FIRRTL 中的 **元数据导出机制**。它允许硬件模块在设计时将参数、配置信息等元数据"附着"在模块上，供下游工具链（如文档生成器、SoC 集成框架、寄存器描述文件生成器）读取，而 **完全不影响综合出的硬件**。

核心思想：把"关于硬件的信息"和"硬件本身"分离，但在同一份源码中维护。

## 在 SPI 项目中的实际代码

### 1. 定义 OM 类

```scala
@instantiable
class SPIOM(parameter: SPIParameter) extends Class {
  val dividerLen:    Property[Int]     = IO(Output(Property[Int]()))
  val maxChar:       Property[Int]     = IO(Output(Property[Int]()))
  val ssNb:          Property[Int]     = IO(Output(Property[Int]()))
  val useAsyncReset: Property[Boolean] = IO(Output(Property[Boolean]()))
  dividerLen    := Property(parameter.dividerLen)
  maxChar       := Property(parameter.maxChar)
  ssNb          := Property(parameter.ssNb)
  useAsyncReset := Property(parameter.useAsyncReset)
}
```

关键点：

| 元素               | 含义                                          |
| ------------------ | --------------------------------------------- |
| `extends Class`    | 不是 `Module`——它是一个"属性类"，不生成硬件   |
| `@instantiable`    | 允许被 `Instantiate()` 创建实例，支持去重优化 |
| `Property[Int]`    | 属性类型，不是 `UInt`/`Bool` 等硬件类型       |
| `:= Property(...)` | 将 Scala 值绑定到 Property 端口               |

### 2. 在接口中声明 OM 端口

```scala
class SPIInterface(parameter: SPIParameter) extends Bundle {
  // ... 硬件端口 ...
  val om = Output(Property[AnyClassType]())
}
```

`AnyClassType` 是所有 OM Class 的通用基类型——类似于面向对象中的 `Object` 类型，允许工具链统一处理不同模块的 OM。

### 3. 在顶层模块中实例化和连接

```scala
// ─── Object Model ──────────────────────────────────────────
val omInstance: Instance[SPIOM] = Instantiate(new SPIOM(parameter))
io.om := omInstance.getPropertyReference.asAnyClassType
```

`getPropertyReference` 获取 OM 实例的引用，`asAnyClassType` 将其向上转型为通用类型。

## 编译产物中的表现

### FIRRTL 中间表示

在 `SPI.fir` 中可以看到 OM 被表达为：

```
class SPIOM :
  output dividerLen : Integer
  output maxChar : Integer
  output ssNb : Integer
  output useAsyncReset : Bool

  propassign dividerLen, Integer(16)
  propassign maxChar, Integer(128)
  propassign ssNb, Integer(8)
  propassign useAsyncReset, Bool(false)
```

注意这里使用的是 `class`（不是 `module`）和 `propassign`（不是 `connect`），`Integer` 也不是硬件类型 `UInt`。

在顶层模块 SPI 中：

```
output om : AnyRef
// ...
object omInstance of SPIOM
propassign om, omInstance
```

### 生成的 SystemVerilog

查看 firtool 生成的 `SPI.sv`，你会发现 **OM 完全消失了**：

```systemverilog
module SPI(
  input         clock,
                reset,
  input  [4:0]  paddr,
  input         psel,
                penable,
                pwrite,
  input  [3:0]  pstrb,
  input  [31:0] pwdata,
  output [31:0] prdata,
  output        pready,
                pslverr,
                intO,
  output [7:0]  ssPadO,
  output        sclkPadO,
                mosiPadO,
  input         misoPadI
);
  // ... 纯硬件逻辑，没有任何 OM 的痕迹 ...
endmodule
```

没有 `om` 端口，没有 `SPIOM` 的实例——一切元数据在 firtool 处理过程中被提取到单独的通道。

## 与其他机制的对比

|                      | `SPIParameter` (JSON) | `SPIOM` (Object Model) | `SPIProbe` (Verification) |
| -------------------- | --------------------- | ---------------------- | ------------------------- |
| **信息来源**         | 编译前的配置          | 编译时从参数派生       | 运行时的硬件信号          |
| **载体**             | JSON 文件             | FIRRTL Property 类型   | FIRRTL Probe 类型         |
| **生成的 SV 中存在** | 否                    | 否                     | 仅启用 Verification 层时  |
| **消费者**           | elaborator            | 下游工具链 (firtool)   | 仿真 / 形式验证           |
| **方向**             | 外部 → Chisel         | Chisel → 外部工具      | 硬件内部 → 验证环境       |

简单来说：
- **Parameter (JSON)**：外部告诉 Chisel "你应该怎么配置"
- **Object Model**：Chisel 告诉外部工具 "我被配置成了什么样"
- **Probe**：硬件运行时告诉验证环境 "我内部发生了什么"

## 一个具体例子：为什么 OM 有用

假设你在做一个 SoC，需要集成多个 SPI 控制器，每个配置不同：

```scala
// SoC 顶层
val spi0 = Module(new SPI(SPIParameter(dividerLen = 8,  maxChar = 32,  ssNb = 2)))
val spi1 = Module(new SPI(SPIParameter(dividerLen = 16, maxChar = 128, ssNb = 8)))
```

**没有 OM 的情况**：SoC 集成工具需要手动维护一份表格，记录 spi0 的最大传输位宽是 32、有 2 个片选线等等。如果 Chisel 参数改了，表格就过期了。

**有 OM 的情况**：firtool 在处理 FIRRTL 时，自动从 OM 中提取出结构化信息。下游工具可以直接查询：

```
spi0.om.maxChar   → 32
spi0.om.ssNb      → 2
spi1.om.maxChar   → 128
spi1.om.ssNb      → 8
```

工具链可以利用这些信息自动完成：

1. **寄存器描述文件生成**：知道 `charLenBits = 5`（因为 `maxChar = 32`），可以自动生成正确位宽的控制寄存器描述
2. **地址映射文档**：知道 `nTxWords = 1`（因为 `maxChar = 32`），只需要 1 个 TX 数据寄存器地址
3. **驱动代码生成**：知道 `ssNb = 2`，片选寄存器只需要 2 位
4. **设计规则检查**：检查 `dividerLen` 是否满足目标时钟频率要求

所有这些信息都从设计源码中"自动流出"，无需手动同步。

## OM 在 chisel-nix 工具链中的位置

```
                    编译前              编译时                编译后
                 ┌──────────┐    ┌───────────────┐    ┌──────────────┐
  SPI.json ────→ │Parameter │──→ │   Chisel      │──→ │  FIRRTL      │
  (配置输入)      │(Scala值)  │    │ (生成硬件+OM) │    │ (hw + prop)  │
                 └──────────┘    └───────────────┘    └──────┬───────┘
                                                             │
                                                        firtool
                                                             │
                                         ┌───────────────────┼──────────────┐
                                         ▼                   ▼              ▼
                                    SystemVerilog      OM (元数据)     Probe (验证)
                                    (纯硬件)         (参数/配置)    (内部信号)
                                         │                   │              │
                                         ▼                   ▼              ▼
                                      综合/仿真        文档/集成工具    仿真/FV
```

firtool 像一个三通阀：把 FIRRTL 中混在一起的硬件、元数据、验证信号分流到各自的输出通道，各取所需。

## 为什么不直接用 JSON

你可能会问：我已经有 `configs/SPI.json` 了，为什么还需要 OM？

关键区别在于 **派生信息**：

```scala
case class SPIParameter(
  maxChar: Int = 128  // JSON 中的原始参数
) {
  val charLenBits: Int = log2Ceil(maxChar)  // 派生值：7
  val nTxWords: Int = (maxChar + 31) / 32   // 派生值：4
}
```

`charLenBits` 和 `nTxWords` 不在 JSON 里，它们是 Scala 在编译时计算出来的。如果工具链只看 JSON，它不知道控制寄存器的位宽字段到底是 7 位还是 8 位。但通过 OM，这些派生值可以一并导出：

```scala
class SPIOM(parameter: SPIParameter) extends Class {
  // 原始参数
  val maxChar: Property[Int] = IO(Output(Property[Int]()))
  maxChar := Property(parameter.maxChar)

  // 也可以导出派生值
  val charLenBits: Property[Int] = IO(Output(Property[Int]()))
  charLenBits := Property(parameter.charLenBits)  // 7
}
```

OM 确保下游工具看到的是 **和硬件完全一致** 的参数视图，不会出现手动同步导致的不一致。
