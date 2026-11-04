# financeAgent

个人自用的 A 股投资助手。单体应用，一个 jar 启动，浏览器直接访问。

## 一期功能

| 模块 | 能力 |
|---|---|
| **市场** | A 股全市场股票与 ETF 的日 K 浏览、个股详情、指标叠加 |
| **选股** | 条件构建器（因子 + 运算符 + 阈值）、预设策略模板、结果一键存为策略 |
| **回测** | 用选股策略做历史回测，输出净值、回撤、绩效指标与交易流水 |

覆盖范围：沪深主板、创业板、科创板、北交所的股票与 ETF，约 6,600 个标的、10 年日 K。

## 技术栈

Java 21 · Spring Boot 3.x · Thymeleaf + HTMX + Alpine.js · ECharts · SQLite(WAL) · MyBatis · Maven Wrapper

数据全部本地化：外部行情抓取后落 SQLite，页面渲染不发起外部请求。

## 快速开始

```bash
# 构建（本机无全局 mvn，使用项目自带的 wrapper）
./mvnw clean package

# 运行
java -jar target/financeAgent-*.jar

# 访问
open http://localhost:8080
```

首次运行需要初始化数据（命令行触发，执行完自动退出）：

```bash
# 标的字典 → 10 年日K → 市场快照 → 指数 → 技术因子 → 历史估值（日K约 2.6 万次请求，建议夜间执行）
java -jar target/financeAgent-*.jar --sync.instruments --sync.init=0 --sync.snapshot --sync.indexes --factor.init --valuation.init \
     --spring.main.web-application-type=none

# 审计历史覆盖，补齐缺口（覆盖审计只读；backfill 可重复执行）
java -jar target/financeAgent-*.jar --sync.audit --spring.main.web-application-type=none
java -jar target/financeAgent-*.jar --sync.backfill=0 --spring.main.web-application-type=none

# 手动执行一次收盘全流程（字典 → 日K → 因子 → 估值 → 快照）
java -jar target/financeAgent-*.jar --sync.daily --spring.main.web-application-type=none
```

**收盘自动同步**：应用运行时默认在交易日 16:10（Asia/Shanghai）自动执行 `--sync.daily`
（`finance.sync.schedule.*` 可调，`enabled=false` 可关闭）。交易日用基准指数当日日K探测，
休市日跳过；应用在收盘后启动且当天未同步时会异步补一次。应用未运行时不会调度，
需要外部 `launchd`/`cron` 拉起 jar（后续工作项）。

覆盖审计按「首根日K是否覆盖历史起点、末根是否覆盖最新交易日」判定，区间内部缺口视为停牌；
`--sync.missing` 只识别完全没有日K的标的，历史缺口请用 `--sync.audit` / `--sync.backfill`。
完整链路与本次回填记录见 `docs/work-items/WI-017-数据覆盖补齐与自动同步.md`。

M1 的 50 只小样本验证数据可通过 `--sync.init=50` 复现。同步任务均有条数校验与幂等保证，失败整批拒绝并记入 `sync_log`；全量初始化中断或出现失败时，可重复执行 `--sync.backfill` 补齐。因子任务可单独触发：`--factor.init` 全量回填，`--factor.update` 增量重算；历史估值同理：`--valuation.init` 回填、`--valuation.update` 增量。

## 批量回测脚本

`script/batch_backtest.py`：从创建/更新策略到批量回测、输出绩效与回撤结果的一体化脚本（Python 3 标准库，无需安装依赖；需应用已启动且与数据库同版本）。

```bash
# 全量跑脚本内置的策略矩阵（牛市 / 熊市 × 各策略建议频率）
python3 script/batch_backtest.py

# 常用参数
python3 script/batch_backtest.py --only 缩量超跌 --windows BEAR --freqs DAILY
python3 script/batch_backtest.py --report          # 仅输出已有结果
python3 script/batch_backtest.py --create-only     # 只创建策略
```

结果输出到终端表格与 `target/batch-backtest/`（state.json 断点续跑，results.json / results.csv 明细；重复执行自动复用已成功的任务，`--force` 可强制重跑）。

## 项目结构

```
AGENTS.md                  项目约束总纲（改代码前先读）
DEVELOPMENT_WORKFLOW.md    风险分级与验证要求
docs/
  ARCHITECTURE.md          架构设计与模块划分
  BACKEND_GUIDE.md         后端编码约束
  FRONTEND_GUIDE.md        前端与页面约束
  DATA_CONTRACT.md         数据口径与语义契约
  DATA_SOURCES.md          数据源规格与容错策略
  work-items/              高风险任务记录
src/main/java/org/haaasaki/financeagent/
  config/  common/         配置与通用
  data/                    数据层：provider（外部源）/ repository（SQL）/ sync（同步任务）
  indicator/               技术指标计算（纯函数）
  screener/                选股：DSL / 因子 / 模板
  backtest/                回测：撮合 / 费用 / 绩效
  market/                  市场模块
  web/                     控制器与视图模型
src/main/resources/
  templates/  static/      页面与静态资源
  db/migration/            SQL 迁移脚本（V*.sql）
script/
  batch_backtest.py        批量回测：创建策略 → 回测 → 绩效/回撤报表
```

## 数据来源

| 数据 | 来源 |
|---|---|
| 日 K 行情 | 腾讯（主）、东财（备） |
| 全市场列表、估值快照 | 新浪 |
| 官方公告 | 巨潮资讯（二期） |
| 结构化事件（解禁 / 业绩预告 / 龙虎榜 / 分红） | 东财（二期） |

均为公开免费接口，无 Key。接口细节、限流特性与容错策略见 `docs/DATA_SOURCES.md`。

**合规**：新闻类内容只存标题、链接与时间，不入库正文，不商用、不再分发。

## 已知边界

- 仅覆盖 A 股，不做港美股。
- 仅日 K，不做分钟级行情与回测。
- 不接券商、不自动下单。
- 回测结果用于策略研究，不构成投资建议。
