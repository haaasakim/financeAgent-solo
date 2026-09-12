# financeAgent Agent Instructions

本文件只保留本项目特有的规则。通用工具、权限与补丁编辑行为由 harness 负责。

## 1. 使用范围

- 用户当前指令优先；规则与用户指令冲突时，说明影响后按用户指令执行。
- 每次任务先看本文件与 `git status --short`。
- 普通改动按 `DEVELOPMENT_WORKFLOW.md` 的普通路径执行，不要求建工作项。
- 涉及数据库结构、数据源 Provider、指标计算、回测、调度、依赖变更时，先读完整工作流与对应专题文档。
- 只修改任务范围内的文件，保留用户已有改动。

## 2. 项目定位

个人自用的 A 股投资助手。**单体应用，前后端不分离**，一个 jar、一个进程、一个 SQLite 文件。

一期范围：

- **选股**——条件构建器 + 预设策略模板，对标同花顺智能选股
- **市场**——A 股全市场股票与 ETF 的日 K 浏览
- **回测**——用选股结果做策略回测

一期明确不做：港美股、分钟级行情与回测、自动下单与券商接入、用户自定义 SQL 或 Python 表达式、退市股、新闻情感分析、多策略组合寻优。

## 3. 技术栈

Java 21 · Spring Boot 3.x · Thymeleaf + HTMX + Alpine.js · ECharts · SQLite(WAL) · Spring JDBC(`JdbcClient`) · Flyway · Maven Wrapper

不引入：微服务、消息队列、Redis、ORM、Node 构建链。

## 4. 项目地图

```
pom.xml
AGENTS.md                    本文件
README.md                    项目说明与快速开始
DEVELOPMENT_WORKFLOW.md      风险分级、验证要求、交付流程
docs/
  ARCHITECTURE.md            分层、模块、包结构、任务模型
  BACKEND_GUIDE.md           后端分层与编码约束
  FRONTEND_GUIDE.md          页面、组件、配色、图表约束
  DATA_CONTRACT.md           字段口径、复权、交易日、涨跌停、空值语义
  DATA_SOURCES.md            外部数据源接口规格与容错策略
  work-items/                高风险任务记录
src/main/java/org/haaasaki/financeagent/
  config/  common/  data/  indicator/  screener/  backtest/  market/  web/
src/main/resources/
  templates/  static/  application.yml
  db/migration/              Flyway 迁移脚本
```

## 5. 文档索引

| 场景 | 必读 |
|---|---|
| 改数据库结构或字段口径 | `docs/DATA_CONTRACT.md` |
| 新增或修改数据源 | `docs/DATA_SOURCES.md` |
| 写后端代码 | `docs/BACKEND_GUIDE.md` |
| 改页面或图表 | `docs/FRONTEND_GUIDE.md` |
| 新增模块或调整分层 | `docs/ARCHITECTURE.md` |
| 判断改动风险与验证要求 | `DEVELOPMENT_WORKFLOW.md` |

## 6. 红线

**金融数据红线（违反即返工）**

- 统一使用 `instrument_id` 关联数据，禁止用裸股票代码跨表关联。
- 数据必须明确市场、币种、时区、交易日、精度、复权方式、来源与可用时点。
- 回测只能使用当时已可用的数据（`available_at <= as_of`），财务数据按**披露时间**而非报告期末过滤。
- 停牌、涨跌停不得被伪造成交；订单状态必须如实记录为延后、部分成交或未成交。
- 缺失数据保持缺失，**禁止用 0 或哨兵值填充**。
- 百分比统一以小数存储（12.5% 存 `0.125`）。
- 不把模拟或估算数据当作真实结果写入正式表。
- 涨跌停判定必须按板块与 ST 状态取值，不得统一按 10%。

**同步与容错红线**

- 任何同步实现必须有**落库条数校验**。腾讯接口在参数超限时会静默降级返回部分数据，HTTP 状态码仍为 200。
- 校验失败时**整批拒绝**，不允许部分写入。
- 同步任务必须幂等，重复执行不产生重复数据。

**工程红线**

- 外部数据访问必须经 Provider 层，禁止在 Controller 或页面中直接发外部请求。
- SQL 必须参数化，用户输入不得拼接进 SQL。
- 不提交数据库文件、日志、构建产物与密钥。
- 用户明确要求才 commit、push。

## 7. 常用命令

```bash
./mvnw clean package        # 构建
./mvnw test                 # 全部测试
./mvnw test -Dtest=IndicatorCalculatorTest   # 单个测试类
java -jar target/financeAgent-*.jar          # 运行，默认 8080
```

> 本机没有全局 `mvn`，统一用项目自带 `./mvnw`。

## 8. Git 与交付

- 提交信息格式：`<type>(<scope>): 中文简述`。
- 一个模块一个提交，不混入无关改动。
- 提交前检查 `git diff --check`、敏感文件与工作区状态。
- 普通任务不要求工作项；高风险、跨模块或需持续跟踪的任务使用 `docs/work-items/` 模板。
