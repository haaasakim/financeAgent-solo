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

首次运行需要初始化数据：启动后进入「数据同步」页触发一次**全市场历史初始化**（约 2.6 万次请求，耗时约 1.5 小时，建议夜间执行）。之后每天收盘后自动增量同步。

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
