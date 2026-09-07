package com.springai.springai.text2sql;

public final class BusinessMetricsDefault {
    public static final String DEFAULT_JSON = """
{
  "metrics": [
    {
      "metric": "设备台账总数",
      "synonyms": [
        "设备总数",
        "deviceCount",
        "machineCount"
      ],
      "definition": "对 device 主表进行 COUNT(1) 统计设备台账记录条数；可按 PRODUCE_TIME 是否为空、grade_id、tag_id 等维度分组。",
      "expression": "select count(1) from DEVICE",
      "grain": "factory",
      "tables": [
        "device"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "DeviceMapper.countByProduceTimeIsNull",
      "confidence": "high"
    },
    {
      "metric": "设备数量按等级分布",
      "synonyms": [
        "设备等级分布",
        "deviceGradeDistribution"
      ],
      "definition": "按 grade_id（设备等级）分组统计设备数量；可选叠加 PRODUCE_TIME 时长过滤（CURRENT_TIMESTAMP - interval '2' year < PRODUCE_TIME）。",
      "expression": "select COALESCE(count(*), 0) as countNumber, grade_id as gradeId from device group by grade_id",
      "grain": "factory",
      "tables": [
        "device"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "DeviceMapper.getCountGradeIdNumber",
      "confidence": "high"
    },
    {
      "metric": "设备投产时长分布",
      "synonyms": [
        "设备年龄分布",
        "deviceAgeDistribution"
      ],
      "definition": "按 CURRENT_TIMESTAMP - PRODUCE_TIME 区间（2 年内/外）UNION 统计设备数量，用 tag_id 或 orderNumber 做标识。",
      "expression": "SELECT COALESCE(count(*), 0), '1' AS orderNumber FROM device WHERE CURRENT_TIMESTAMP - interval '2' year < PRODUCE_TIME UNION SELECT COALESCE(count(*), 0), '2' AS orderNumber FROM device WHERE CURRENT_TIMESTAMP - interval '2' year >= PRODUCE_TIME",
      "grain": "time",
      "tables": [
        "device"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "DeviceMapper.selectByDeviceAge",
      "confidence": "high"
    },
    {
      "metric": "在线设备状态分布",
      "synonyms": [
        "设备状态数",
        "设备实时状态",
        "onlineDeviceCount"
      ],
      "definition": "按 DEVICE_STATE 取值分类统计在线设备：3=加工中→processNumber；0/10/11/12/13=停机→shutdownNumber；1=暂停→suspendNumber；2=热机→hotNumber；用于生产看板与实时监控大屏。",
      "expression": "SELECT SUM(CASE WHEN DEVICE_STATE = 3 THEN 1 ELSE 0 END) AS processNumber, SUM(CASE WHEN DEVICE_STATE IN (0,10,11,12,13) THEN 1 ELSE 0 END) AS shutdownNumber, SUM(CASE WHEN DEVICE_STATE = 1 THEN 1 ELSE 0 END) AS suspendNumber, SUM(CASE WHEN DEVICE_STATE = 2 THEN 1 ELSE 0 END) AS hotNumber FROM DEVICE_STATE",
      "grain": "factory",
      "tables": [
        "device_state",
        "tmp_batch_device_id"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "DeviceMapper.productionNumberList",
      "confidence": "high"
    },
    {
      "metric": "锁机设备数",
      "synonyms": [
        "锁机数量",
        "lockedMachineCount"
      ],
      "definition": "统计 lock_machineinfo 中 lockState in (0,2) 且 lockMachineType != 8 的锁机记录数；用于送检 NG / 换刀首件 / 连续偏移等锁机原因判定。",
      "expression": "select count(1) from LOCK_MACHINEINFO where machineNumber=#{machineNumber} and lockState in (0,2) and lockMachineType!=8",
      "grain": "device",
      "tables": [
        "lock_machineinfo"
      ],
      "excluded_conditions": [],
      "source": "LockMachineInfoMapper.isLockMachineNumber",
      "confidence": "high"
    },
    {
      "metric": "停机时长按设备聚合",
      "synonyms": [
        "设备停机时长",
        "downtimeDurationByDevice"
      ],
      "definition": "按设备 device_id 分组聚合 downtime_record.DURATION_MILLS（毫秒）；关联 reason 表解析 level2 原因；时间维度按 REAL_START_TIME 按天截取。",
      "expression": "SELECT d.device_id, sum(d.DURATION_MILLS) AS durationMills, to_char(d.REAL_START_TIME, 'yyyy-MM-dd') AS realStartTime FROM DOWNTIME_RECORD d LEFT JOIN reason r ON d.reason_id = r.id WHERE d.SOURCE = 1 AND d.REAL_START_TIME >= CURRENT_TIMESTAMP - INTERVAL '7 days' GROUP BY d.device_id, to_char(d.REAL_START_TIME, 'yyyy-MM-dd')",
      "grain": "device",
      "tables": [
        "downtime_record",
        "reason"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "DowntimeRecordMapper.selectByReasonId",
      "confidence": "high"
    },
    {
      "metric": "停机时长按天聚合",
      "synonyms": [
        "每日停机秒数",
        "downtimeSecondsByDay"
      ],
      "definition": "将 downtime_record.DURATION_MILLS/1000 转为秒，按 to_char(end_time,'YYYY-MM-DD') 当天（含 08:00 班次切换偏移）聚合；用于未归类停机统计。",
      "expression": "SELECT case WHEN to_char(end_time,'HH24') <= '08' then TO_CHAR(end_time - INTERVAL '8 hours 1 second','YYYY-MM-DD') ELSE TO_CHAR(end_time,'YYYY-MM-DD') END AS end_time_new, sum(DURATION_MILLS/1000) run_time FROM downtime_record WHERE end_time IS NOT NULL GROUP BY end_time_new",
      "grain": "time",
      "tables": [
        "downtime_record",
        "tmp_batch_device_id"
      ],
      "excluded_conditions": [],
      "source": "DeviceOperationReportMapper.notClassifiedByDate",
      "confidence": "high"
    },
    {
      "metric": "设备稼动时长",
      "synonyms": [
        "运行时间",
        "runTime",
        "minorShutdownSeconds"
      ],
      "definition": "从 device_operation_report.RUN_TIME 字段按设备 / 时间维度聚合运行秒数；常按 08:00 班次切换做日期偏移归集。",
      "expression": "SELECT coalesce(sum(run_time),0) run_time, device_id FROM DEVICE_OPERATION_REPORT WHERE end_time IS NOT NULL GROUP BY device_id",
      "grain": "device",
      "tables": [
        "device_operation_report",
        "tmp_batch_device_id"
      ],
      "excluded_conditions": [],
      "source": "DeviceOperationReportMapper.selectMinorShutdown1",
      "confidence": "high"
    },
    {
      "metric": "OEE 综合（时间×性能×合格率）",
      "synonyms": [
        "OEE",
        "综合设备效率",
        "overallEquipmentEffectiveness"
      ],
      "definition": "OEE = SUM(VALUABLE_TIME) / SUM(PLAN_RUN_TIME) * 100（已含时间稼动率×性能稼动率×合格率复合）；按 FACTOTY_ID/PROCESS_ID 维度聚合，对应 oee_core_index_report 中 VALUABLE_TIME / PLAN_RUN_TIME 字段。",
      "expression": "SELECT FACTOTY_ID,FACTOTY_NAME,PROCESS_ID,PROCESS_NAME, ROUND(CASE WHEN SUM(COALESCE(PLAN_RUN_TIME,0)) = 0 THEN 0 ELSE SUM(COALESCE(VALUABLE_TIME,0)) / SUM(COALESCE(PLAN_RUN_TIME,0)) END * 100, 2) OEE FROM oee_core_index_report GROUP BY FACTOTY_ID,FACTOTY_NAME,PROCESS_ID,PROCESS_NAME",
      "grain": "station",
      "tables": [
        "oee_core_index_report",
        "work_station",
        "sys_dict_data"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "UptimeMapper.processList",
      "confidence": "high"
    },
    {
      "metric": "工位 OEE 平均",
      "synonyms": [
        "stationOeeAvg",
        "工位综合效率"
      ],
      "definition": "按工位 station_name 分组求 OEE_CORE_INDEX_REPORT.OEE 平均值；同期计算理论节拍时间 stationTotalTime、单机可用台时。",
      "expression": "SELECT station_id, station_name, AVG(oee) oee, SUM(oee) sumOee, count(distinct(dl.MACHINE_ID)) machineNumber FROM OEE_CORE_INDEX_REPORT o INNER JOIN device_level dl ON dl.MACHINE_ID = o.device_id GROUP BY station_id, station_name",
      "grain": "station",
      "tables": [
        "device_level",
        "oee_core_index_report"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "OeeCoreIndexReportMapper.selectSumShutdownExport",
      "confidence": "medium"
    },
    {
      "metric": "OEE 自因损失分析",
      "synonyms": [
        "OEE损失原因",
        "suspenMinorHeatEngine"
      ],
      "definition": "按 START_TIME 按天聚合 SUSPEN（待机）+ MINOR_SHUTDOWN（轻微停机）+ HEAT_ENGINE（热机）+ UNABLE_TO_CONNECT（断连）的损失秒数；用于 OEE 自因拆解。",
      "expression": "SELECT TO_CHAR(START_TIME,'YYYY-MM-DD') xAxial, sum(MINOR_SHUTDOWN) MINOR_SHUTDOWN, sum(SUSPEN) SUSPEN, sum(HEAT_ENGINE) HEAT_ENGINE, sum(UNABLE_TO_CONNECT) UNABLE_TO_CONNECT FROM OEE_CORE_INDEX_REPORT GROUP BY TO_CHAR(START_TIME,'YYYY-MM-DD')",
      "grain": "time",
      "tables": [
        "oee_core_index_report",
        "tmp_batch_device_id"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "OeeCoreIndexReportMapper.selectBySelfReason",
      "confidence": "high"
    },
    {
      "metric": "OEE 多维统计（含可用率与缺陷）",
      "synonyms": [
        "oeeAvailability",
        "defectNum",
        "qualityLoss"
      ],
      "definition": "从 oee_report 取 ACTUAL_PARTS_COUNT/DEFECT_NUM/AVAILABILITY/SUSPEN/MINOR_SHUTDOWN/UNPLAN_DOWNTIME 关键维度；按设备/项目/工位/班次多维聚合，含良品率计算。",
      "expression": "select o.DEVICE_ID, o.PARTS_ID, sum(o.ACTUAL_PARTS_COUNT) ACTUAL_PARTS_COUNT, sum(o.DEFECT_NUM) DEFECT_NUM, avg(o.AVAILABILITY) AVAILABILITY, sum(o.SUSPEN) SUSPEN, sum(o.MINOR_SHUTDOWN) MINOR_SHUTDOWN, sum(o.UNPLAN_DOWNTIME) UNPLAN_DOWNTIME FROM OEE_REPORT o GROUP BY o.DEVICE_ID, o.PARTS_ID",
      "grain": "device",
      "tables": [
        "device",
        "device_program_relation",
        "oee_report"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "OeeReportMapper.selectStatisticByCondition",
      "confidence": "high"
    },
    {
      "metric": "良品率",
      "synonyms": [
        "合格率",
        "goodRate",
        "qualityYield"
      ],
      "definition": "良品率 = SUM(ACTUAL_PARTS_COUNT - DEFECT_NUM) / SUM(ACTUAL_PARTS_COUNT)，基于 oee_report 缺陷数字段；device_operation_report 也用 ACTUAL_PARTS_COUNT 但无显式 defect 列。",
      "expression": "SELECT CASE WHEN SUM(ACTUAL_PARTS_COUNT) = 0 THEN 0 ELSE (SUM(ACTUAL_PARTS_COUNT) - SUM(DEFECT_NUM)) * 1.0 / SUM(ACTUAL_PARTS_COUNT) END AS goodRate FROM oee_report WHERE start_time >= #{startTime} AND start_time < #{endTime}",
      "grain": "time",
      "tables": [
        "device",
        "oee_report"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "OeeReportMapper.selectByCondition",
      "confidence": "medium"
    },
    {
      "metric": "实际产量",
      "synonyms": [
        "零件数",
        "产出数",
        "actualPartsCount"
      ],
      "definition": "对 device_operation_report.ACTUAL_PARTS_COUNT 求 SUM（排除未结束记录或 ACTUAL_PARTS_COUNT >= 1）；可按 device_id / 时间窗聚合，常用于产能监控。",
      "expression": "select coalesce(sum(ACTUAL_PARTS_COUNT), 0) from device_operation_report where start_time >= TO_TIMESTAMP(#{startTime}, 'YYYY-MM-DD HH24:MI:SS') and start_time < TO_TIMESTAMP(#{endTime}, 'YYYY-MM-DD HH24:MI:SS') and device_id = #{deviceId}",
      "grain": "device",
      "tables": [
        "device_operation_report"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "DeviceOperationReportMapper.selectActualPartsCount",
      "confidence": "high"
    },
    {
      "metric": "产量按工序聚合",
      "synonyms": [
        "按工序零件数",
        "partsByProcess"
      ],
      "definition": "left join oee_core_index_report (type=2) 取 ACTUAL_PARTS_COUNT，按 process_name 分组求和，按日/班次/项目多维切片；capacityNumber 默认 0 占位。",
      "expression": "select sum(o.ACTUAL_PARTS_COUNT) partsNumber, dl.process_name processName, dl.process_id processId from DEVICE_LEVEL dl left join OEE_CORE_INDEX_REPORT o on dl.MACHINE_ID = o.device_id and o.type = 2 where o.start_time >= to_timestamp(#{startTime}, 'yyyy-mm-dd hh24:mi:ss') GROUP BY dl.process_name, dl.process_id",
      "grain": "project",
      "tables": [
        "device_level",
        "oee_core_index_report"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "DeviceMapper.quantityByProcess",
      "confidence": "high"
    },
    {
      "metric": "换刀记录查询",
      "synonyms": [
        "刀具更换记录",
        "toolLifeList",
        "toolChangeRecord"
      ],
      "definition": "从 tool_exchange / tool_lifecycle 查换刀明细：含 exchange_reason、tool_position、tool_materiel、rated_lifetime 等；多表 JOIN device_program_relation / job / parts / operation / tool_job_relation 解析项目/工序。",
      "expression": "SELECT te.id, te.exchange_reason, p.id partsId, p.PARTS_NAME partsName, op.id operationId, op.NAME operationName, j.id jobId, j.jobName jobName, tjr.tool_position, t.tool_materiel FROM TOOL_EXCHANGE te LEFT JOIN DEVICE_PROGRAM_RELATION dpr ON te.device_id = dpr.device_id LEFT JOIN tool_job_relation tjr ON tjr.job_id = dpr.job_id LEFT JOIN tool t ON t.id = tjr.tool_id LEFT JOIN parts p ON p.id = dpr.parts_id LEFT JOIN operation op ON op.id = dpr.operation_id LEFT JOIN job j ON j.id = dpr.job_id",
      "grain": "device",
      "tables": [
        "device_program_relation",
        "job",
        "operation",
        "parts",
        "tool",
        "tool_exchange",
        "tool_job_relation"
      ],
      "excluded_conditions": [],
      "source": "ToolExchangeMapper.selectToolLifeList",
      "confidence": "medium"
    },
    {
      "metric": "换刀次数按日统计",
      "synonyms": [
        "日换刀次数",
        "dailyToolChangeCount"
      ],
      "definition": "按 device_id / 日期分组统计换刀次数；输出 device_name、project_name、station_name、cell_name、更新日期与小时分钟，用于换刀履历看板。",
      "expression": "select to_char(tl.update_time, 'yyyy-mm-dd') as toDate, to_char(tl.update_time, 'hh24:mi:ss') as toTime, dpr.device_name as devicename, dpr.DEVICE_ID as deviceId, tjb.tool_position as cutterSpacing FROM TOOL_LIFECYCLE tl INNER JOIN DEVICE_PROGRAM_RELATION dpr ON tl.device_id = dpr.device_id LEFT JOIN tool_job_relation tjb ON dpr.job_id = tjb.job_id",
      "grain": "time",
      "tables": [
        "device_program_relation",
        "job",
        "operation",
        "parts",
        "tool",
        "tool_job_relation",
        "tool_lifecycle",
        "tool_prepare_record",
        "tool_qrcode",
        "tool_supplier"
      ],
      "excluded_conditions": [],
      "source": "ToolLifecycleMapper.toolChangeRecordQuery",
      "confidence": "medium"
    },
    {
      "metric": "班次维度时间轴",
      "synonyms": [
        "白班/晚班分组",
        "shiftGrouping"
      ],
      "definition": "oee_core_index_report.type=1 时按 shift_id 区分（1=晚班，其余=白班），type=2 为全天；按设备-项目-工序-班次多维聚合生成生产报表时间轴。",
      "expression": "SELECT CASE WHEN o.type = 1 THEN CASE o.shift_id WHEN 1 THEN '晚班' ELSE '白班' END ELSE '全天' END AS shift_name, dl.factoty_name, dl.project_name, dl.process_name, sum(o.ACTUAL_PARTS_COUNT) qty FROM OEE_CORE_INDEX_REPORT o LEFT JOIN device_level dl ON dl.MACHINE_ID = o.device_id GROUP BY shift_name, dl.factoty_name, dl.project_name, dl.process_name",
      "grain": "shift",
      "tables": [
        "device_level",
        "device_program_relation",
        "job",
        "oee_core_index_report"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "OeeCoreIndexReportMapper.newProductionStatement",
      "confidence": "high"
    },
    {
      "metric": "时间轴 GROUP BY 天",
      "synonyms": [
        "按日聚合",
        "dailyAxis"
      ],
      "definition": "对 OEE_CORE_INDEX_REPORT/OEE_REPORT 用 TO_CHAR(START_TIME,'YYYY-MM-DD') 按天聚合；常用于日报表与 OEE 趋势曲线。",
      "expression": "SELECT TO_CHAR(START_TIME,'YYYY-MM-DD') YYYYMMDD, ROUND(CASE WHEN SUM(COALESCE(PLAN_RUN_TIME,0)) = 0 THEN 0 ELSE SUM(COALESCE(VALUABLE_TIME,0))/SUM(COALESCE(PLAN_RUN_TIME,0)) END * 100, 2) OEE_ALL FROM OEE_CORE_INDEX_REPORT GROUP BY TO_CHAR(START_TIME,'YYYY-MM-DD')",
      "grain": "time",
      "tables": [
        "oee_core_index_report",
        "sys_dict_data"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "UptimeMapper.selectFactoryLine",
      "confidence": "high"
    },
    {
      "metric": "时间轴 GROUP BY 工厂/项目/工序",
      "synonyms": [
        "factoryProjectProcessAxis"
      ],
      "definition": "按 FACTOTY_ID, FACTOTY_NAME, PROJECT_ID, PROJECT_NAME, PROCESS_ID, PROCESS_NAME, CELL_ID, CELL_NAME, STATION_ID, STATION_NAME, MACHINE_ID, MACHINE_NAME 多层级聚合；支持 Bar（汇总）/Line（趋势）两种视图。",
      "expression": "SELECT FACTOTY_ID,FACTOTY_NAME,PROJECT_ID,PROJECT_NAME,PROCESS_ID,PROCESS_NAME,TO_CHAR(START_TIME,'YYYY-MM-DD') YYYYMMDD, ROUND(CASE WHEN SUM(COALESCE(PLAN_RUN_TIME,0))=0 THEN 0 ELSE SUM(COALESCE(VALUABLE_TIME,0))/SUM(COALESCE(PLAN_RUN_TIME,0)) END * 100, 2) OEE_ALL FROM OEE_CORE_INDEX_REPORT GROUP BY FACTOTY_ID,FACTOTY_NAME,PROJECT_ID,PROJECT_NAME,PROCESS_ID,PROCESS_NAME,TO_CHAR(START_TIME,'YYYY-MM-DD')",
      "grain": "project",
      "tables": [
        "oee_core_index_report",
        "sys_dict_data"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "UptimeMapper.selectProjectLine",
      "confidence": "high"
    },
    {
      "metric": "设备生产总览（多表 JOIN）",
      "synonyms": [
        "productionList",
        "dashboardJoin"
      ],
      "definition": "9 表 JOIN：device + device_level + device_operation_report + device_state + downtime_record + job + oee_core_index_report + reason + tmp_batch_device_id；输出设备 ID/名称/工序/状态/原因；device_state 0=停机 1=暂停 2=热机 3=运行。",
      "expression": "SELECT dl.machine_id deviceId, dl.machine_name deviceName, dl.station_name operationName, ds.device_state state, dl.process_name deviceType, r.level2 info, dor.RUN_TIME, dor.ACTUAL_PARTS_COUNT FROM device_level dl INNER JOIN device d ON d.id = dl.machine_id LEFT JOIN device_state ds ON ds.device_id = d.id LEFT JOIN device_operation_report dor ON dor.device_id = d.id LEFT JOIN downtime_record dr ON dr.device_operation_report_id = dor.id LEFT JOIN reason r ON r.id = dr.reason_id LEFT JOIN oee_core_index_report o ON o.device_id = d.id LEFT JOIN job j ON j.id = dl.job_id",
      "grain": "device",
      "tables": [
        "device",
        "device_level",
        "device_operation_report",
        "device_state",
        "downtime_record",
        "job",
        "oee_core_index_report",
        "reason",
        "tmp_batch_device_id"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "DeviceMapper.productionList",
      "confidence": "high"
    },
    {
      "metric": "停机归类与原因多表 JOIN",
      "synonyms": [
        "downtimeClassification",
        "未归类停机"
      ],
      "definition": "device_level JOIN downtime_record JOIN reason JOIN tmp_batch_device_id；按 dateRange / factoryName / projectName / processName 维度聚合未归类停机（reason_id IS NULL 或 st_reason_id 缺失），用于停机报表与 IMQ 联动。",
      "expression": "SELECT TO_CHAR(TO_TIMESTAMP(#{startDate},'YYYY-MM-DD HH24:MI:SS'),'MM/DD') || '-' || TO_CHAR(TO_TIMESTAMP(#{endDate},'YYYY-MM-DD HH24:MI:SS') - INTERVAL '1 day','MM/DD') AS dateRange, max(dl.FACTOTY_NAME) factoryName, max(dl.PROJECT_NAME) projectName, max(dl.PROCESS_NAME) processName, sum(dr.DURATION_MILLS/1000) durationSec FROM DOWNTIME_RECORD dr INNER JOIN TMP_BATCH_DEVICE_ID t ON dr.device_id = t.TMP_ID INNER JOIN device_level dl ON dl.MACHINE_ID = dr.device_id LEFT JOIN reason r ON r.id = dr.reason_id GROUP BY dateRange, dl.FACTOTY_NAME, dl.PROJECT_NAME, dl.PROCESS_NAME",
      "grain": "project",
      "tables": [
        "device_level",
        "downtime_record",
        "reason",
        "tmp_batch_device_id"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "source": "DowntimeRecordMapper.selectNotClassified",
      "confidence": "medium"
    },
    {
      "metric": "运行中设备清单",
      "synonyms": [
        "正在跑的设备",
        "跑着的设备",
        "正在运行的设备",
        "在线运行设备",
        "runDeviceList",
        "onlineRunningDevice"
      ],
      "definition": "device_state 与 DEVICE_OPERATION_REPORT 关联，取 end_time IS NULL 的所有运行中设备全字段；用于实时大屏与停机预警。",
      "expression": "select dor.* from device_state ds inner join DEVICE_OPERATION_REPORT dor on ds.DEVICE_OPERATION_REPORT_ID = dor.id where dor.end_time is null",
      "expression_skeleton": "select dor.* from device_state ds inner join DEVICE_OPERATION_REPORT dor on ds.DEVICE_OPERATION_REPORT_ID = dor.id where dor.end_time is null",
      "param_examples": {},
      "param_types": {},
      "grain": "device",
      "tables": [
        "device_operation_report",
        "device_state"
      ],
      "excluded_conditions": [
        "dor.end_time is null"
      ],
      "grain_unique": "与其他 grain=time 的 OEE 报表不冲突；与 v1『在线设备状态分布』互补（v1 按状态分组聚合，本条给明细）",
      "user_questions": [
        "现在哪些设备在跑",
        "正在运行的设备有哪些",
        "给我列出所有正在跑的设备",
        "在线运行设备清单"
      ],
      "source": "DeviceOperationReportMapper.selectRunDevice",
      "confidence": "high"
    },
    {
      "metric": "设备当前活动报表",
      "synonyms": [
        "设备最后一条运行记录",
        "设备正在做的作业",
        "设备当前作业",
        "lastActiveReport",
        "deviceCurrentJob"
      ],
      "definition": "每台设备最后一条 end_time IS NULL 的运行记录；用于设备当前状态判定与作业回填。",
      "expression": "select ID, FACTORY_ID, START_TIME, COALESCE(END_TIME, CURRENT_TIMESTAMP) END_TIME, DEVICE_ID, JOB_ID, OPERATION_ID, IS_LAST, PARTS_ID, STATE, RUN_TIME from DEVICE_OPERATION_REPORT where device_id = ? and end_time is null",
      "expression_skeleton": "select ID, FACTORY_ID, START_TIME, COALESCE(END_TIME, CURRENT_TIMESTAMP) END_TIME, DEVICE_ID, JOB_ID, OPERATION_ID, IS_LAST, PARTS_ID, STATE, RUN_TIME from DEVICE_OPERATION_REPORT where device_id = ? and end_time is null",
      "param_examples": {
        "deviceId": "12345"
      },
      "param_types": {
        "deviceId": "BIGINT"
      },
      "grain": "device",
      "tables": [
        "device_operation_report"
      ],
      "excluded_conditions": [
        "end_time is null"
      ],
      "grain_unique": "单设备维度；与 grain=time 的『最近24小时运行报表』互补",
      "user_questions": [
        "设备12345现在在做什么",
        "这台设备当前在跑哪个作业",
        "设备的当前活动记录",
        "某设备最后一条运行报表"
      ],
      "source": "DeviceOperationReportMapper.selectByLastDeviceId",
      "confidence": "high"
    },
    {
      "metric": "最近24小时运行报表",
      "synonyms": [
        "24小时运行记录",
        "近一天运行",
        "今日运行",
        "last24hRunning",
        "todayRunningReport"
      ],
      "definition": "state='3'（加工中）且开始时间在最近24小时内的运行报表；用于实时看板与近期作业监控。",
      "expression": "select ID, FACTORY_ID, START_TIME, COALESCE(END_TIME, CURRENT_TIMESTAMP) end_time, DEVICE_ID, JOB_ID, OPERATION_ID, IS_LAST, PARTS_ID, STATE, RUN_TIME from DEVICE_OPERATION_REPORT where state = '3' and start_time >= current_timestamp - INTERVAL '1 day'",
      "expression_skeleton": "select ID, FACTORY_ID, START_TIME, COALESCE(END_TIME, CURRENT_TIMESTAMP) end_time, DEVICE_ID, JOB_ID, OPERATION_ID, IS_LAST, PARTS_ID, STATE, RUN_TIME from DEVICE_OPERATION_REPORT where state = '3' and start_time >= current_timestamp - INTERVAL '1 day'",
      "param_examples": {},
      "param_types": {},
      "grain": "time",
      "tables": [
        "device_operation_report"
      ],
      "excluded_conditions": [
        "state = '3'",
        "start_time >= now - 24h"
      ],
      "grain_unique": "近实时；超过 24h 的查询改用 v1『设备生产总览』",
      "user_questions": [
        "最近24小时设备跑了哪些",
        "今天运行的报表",
        "近一天有哪些设备在加工"
      ],
      "source": "DeviceOperationReportMapper.selectOperatingData",
      "confidence": "high"
    },
    {
      "metric": "按设备批量产量排行",
      "synonyms": [
        "设备产量排名",
        "每台设备产量",
        "actualPartsCountByDevice",
        "productionRanking"
      ],
      "definition": "按 device_id 聚合 ACTUAL_PARTS_COUNT，给出每台设备的产量；常用于产能排名与异常产量告警。DURATION_MILLS 单位是毫秒，ACTUAL_PARTS_COUNT 单位是个。",
      "expression": "select device_id, coalesce(sum(ACTUAL_PARTS_COUNT), 0) as actualPartsCount from DEVICE_OPERATION_REPORT where start_time >= to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') and start_time < to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') and device_id in (?) group by device_id",
      "expression_skeleton": "select device_id, coalesce(sum(ACTUAL_PARTS_COUNT), 0) as actualPartsCount from DEVICE_OPERATION_REPORT where start_time >= to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') and start_time < to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') and device_id in (?) group by device_id",
      "param_examples": {
        "startTime": "'2026-08-01 00:00:00'",
        "endTime": "'2026-08-19 23:59:59'",
        "deviceIds": "ARRAY[12345, 67890]"
      },
      "param_types": {
        "startTime": "TIMESTAMP",
        "endTime": "TIMESTAMP",
        "deviceIds": "BIGINT[]"
      },
      "grain": "device",
      "tables": [
        "device_operation_report"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "grain_unique": "批量维度；单设备直接用 v1『实际产量』即可",
      "user_questions": [
        "每台设备产量是多少",
        "设备产量排行",
        "这一周哪台设备产量最高",
        "统计每台设备的实际产量"
      ],
      "source": "DeviceOperationReportMapper.selectActualPartsCountBatch",
      "confidence": "high"
    },
    {
      "metric": "小时级产能",
      "synonyms": [
        "按小时产量",
        "每小时产出",
        "hourCapacity",
        "hourlyOutput"
      ],
      "definition": "按 CELL 单元 + 小时聚合 ACTUAL_PARTS_COUNT；按35分钟偏移（08:00 班次切换）切分时间桶。注：DURATION_MILLIS 是毫秒，ACTUAL_PARTS_COUNT 是个数。",
      "expression": "select dl.CELL_NAME as cellName, to_char(date_trunc('hour', dor.START_TIME - interval '35 minutes') + interval '35 minutes', 'HH24:MI') AS hourTime, COALESCE(SUM(dor.ACTUAL_PARTS_COUNT), 0) as capacity from DEVICE_OPERATION_REPORT dor LEFT JOIN device_level dl ON dl.MACHINE_ID = dor.DEVICE_ID where dor.START_TIME >= to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') and dor.START_TIME < to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') group by dl.CELL_NAME, hourTime order by hourTime",
      "expression_skeleton": "select dl.CELL_NAME as cellName, to_char(date_trunc('hour', dor.START_TIME - interval '35 minutes') + interval '35 minutes', 'HH24:MI') AS hourTime, COALESCE(SUM(dor.ACTUAL_PARTS_COUNT), 0) as capacity from DEVICE_OPERATION_REPORT dor LEFT JOIN device_level dl ON dl.MACHINE_ID = dor.DEVICE_ID where dor.START_TIME >= to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') and dor.START_TIME < to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') group by dl.CELL_NAME, hourTime order by hourTime",
      "param_examples": {
        "startTime": "'2026-08-19 00:00:00'",
        "endTime": "'2026-08-19 23:59:59'"
      },
      "param_types": {
        "startTime": "TIMESTAMP",
        "endTime": "TIMESTAMP"
      },
      "grain": "time",
      "tables": [
        "device_operation_report",
        "device_level"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "grain_unique": "小时粒度只适合 24h 内查询；超过此范围改用 grain=time 但日报",
      "user_questions": [
        "每小时产量",
        "产能按小时统计",
        "今天每个小时产出多少",
        "按小时看产能"
      ],
      "source": "DeviceOperationReportMapper.selectHourCapacityData",
      "confidence": "medium"
    },
    {
      "metric": "设备级 OEE 趋势",
      "synonyms": [
        "设备OEE",
        "每台设备OEE",
        "machineOeeTrend",
        "deviceOeeTrend"
      ],
      "definition": "按 MACHINE_ID × 日聚合 OEE_CORE_INDEX_REPORT 的 OEE。PLAN_RUN_TIME 单位秒；OEE = VALUABLE_TIME / PLAN_RUN_TIME × 100。",
      "expression": "SELECT FACTOTY_ID, FACTOTY_NAME, PROJECT_ID, PROJECT_NAME, PROCESS_ID, PROCESS_NAME, CELL_ID, CELL_NAME, STATION_ID, STATION_NAME, MACHINE_ID, MACHINE_NAME, TO_CHAR(START_TIME,'YYYY-MM-DD') YYYYMMDD, ROUND(CASE WHEN SUM(COALESCE(PLAN_RUN_TIME,0))=0 THEN 0 ELSE SUM(COALESCE(VALUABLE_TIME,0))/SUM(COALESCE(PLAN_RUN_TIME,0)) END * 100, 2) OEE_ALL FROM OEE_CORE_INDEX_REPORT where START_TIME >= to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') AND START_TIME <= to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') GROUP BY FACTOTY_ID,FACTOTY_NAME,PROJECT_ID,PROJECT_NAME,PROCESS_ID,PROCESS_NAME,CELL_ID,CELL_NAME,STATION_ID,STATION_NAME,MACHINE_ID,MACHINE_NAME,TO_CHAR(START_TIME,'YYYY-MM-DD')",
      "expression_skeleton": "SELECT FACTOTY_ID, FACTOTY_NAME, PROJECT_ID, PROJECT_NAME, PROCESS_ID, PROCESS_NAME, CELL_ID, CELL_NAME, STATION_ID, STATION_NAME, MACHINE_ID, MACHINE_NAME, TO_CHAR(START_TIME,'YYYY-MM-DD') YYYYMMDD, ROUND(CASE WHEN SUM(COALESCE(PLAN_RUN_TIME,0))=0 THEN 0 ELSE SUM(COALESCE(VALUABLE_TIME,0))/SUM(COALESCE(PLAN_RUN_TIME,0)) END * 100, 2) OEE_ALL FROM OEE_CORE_INDEX_REPORT where START_TIME >= to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') AND START_TIME <= to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') GROUP BY FACTOTY_ID,FACTOTY_NAME,PROJECT_ID,PROJECT_NAME,PROCESS_ID,PROCESS_NAME,CELL_ID,CELL_NAME,STATION_ID,STATION_NAME,MACHINE_ID,MACHINE_NAME,TO_CHAR(START_TIME,'YYYY-MM-DD')",
      "param_examples": {
        "startDate": "'2026-08-01 00:00:00'",
        "endDate": "'2026-08-19 23:59:59'"
      },
      "param_types": {
        "startDate": "TIMESTAMP",
        "endDate": "TIMESTAMP"
      },
      "grain": "device",
      "tables": [
        "oee_core_index_report",
        "sys_dict_data"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "grain_unique": "grain=device；若问题说『工位 OEE』改用 v2-08『工位级 OEE 平均』",
      "user_questions": [
        "每台设备的 OEE 是多少",
        "设备 OEE 趋势",
        "设备级 OEE 排行",
        "我每台设备的 OEE"
      ],
      "source": "UptimeMapper.selectMachineLine / OeeCoreIndexReportMapper.selectMachineLine",
      "confidence": "high"
    },
    {
      "metric": "班组（Cell）级 OEE 趋势",
      "synonyms": [
        "班组OEE",
        "班组综合效率",
        "cellOeeTrend",
        "shiftOEE"
      ],
      "definition": "按 CELL_ID × 日聚合 OEE；用于班组横向对比与班次绩效评估。",
      "expression": "SELECT FACTOTY_ID, FACTOTY_NAME, PROJECT_ID, PROJECT_NAME, PROCESS_ID, PROCESS_NAME, CELL_ID, CELL_NAME, TO_CHAR(START_TIME,'YYYY-MM-DD') YYYYMMDD, ROUND(CASE WHEN SUM(COALESCE(PLAN_RUN_TIME,0))=0 THEN 0 ELSE SUM(COALESCE(VALUABLE_TIME,0))/SUM(COALESCE(PLAN_RUN_TIME,0)) END * 100, 2) OEE_ALL FROM OEE_CORE_INDEX_REPORT GROUP BY FACTOTY_ID,FACTOTY_NAME,PROJECT_ID,PROJECT_NAME,PROCESS_ID,PROCESS_NAME,CELL_ID,CELL_NAME,TO_CHAR(START_TIME,'YYYY-MM-DD')",
      "expression_skeleton": "SELECT FACTOTY_ID, FACTOTY_NAME, PROJECT_ID, PROJECT_NAME, PROCESS_ID, PROCESS_NAME, CELL_ID, CELL_NAME, TO_CHAR(START_TIME,'YYYY-MM-DD') YYYYMMDD, ROUND(CASE WHEN SUM(COALESCE(PLAN_RUN_TIME,0))=0 THEN 0 ELSE SUM(COALESCE(VALUABLE_TIME,0))/SUM(COALESCE(PLAN_RUN_TIME,0)) END * 100, 2) OEE_ALL FROM OEE_CORE_INDEX_REPORT GROUP BY FACTOTY_ID,FACTOTY_NAME,PROJECT_ID,PROJECT_NAME,PROCESS_ID,PROCESS_NAME,CELL_ID,CELL_NAME,TO_CHAR(START_TIME,'YYYY-MM-DD')",
      "param_examples": {},
      "param_types": {},
      "grain": "time",
      "tables": [
        "oee_core_index_report",
        "sys_dict_data"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "grain_unique": "grain=time + 班组维度；不要与 grain=device 的『设备级 OEE 趋势』同时召回",
      "user_questions": [
        "班组 OEE",
        "班次 OEE 对比",
        "每个班组的综合效率",
        "白班晚班 OEE"
      ],
      "source": "UptimeMapper.selectCellLine",
      "confidence": "high"
    },
    {
      "metric": "工位（Station）级 OEE 平均",
      "synonyms": [
        "工位OEE",
        "stationOeeAvg",
        "工位综合效率"
      ],
      "definition": "按 STATION_ID 分组求 OEE 平均值，含理论节拍时间、单机可用台时；用于工位级 OEE 评估。",
      "expression": "SELECT station_id, station_name, AVG(oee) oee, SUM(oee) sumOee, count(distinct(dl.MACHINE_ID)) machineNumber FROM OEE_CORE_INDEX_REPORT o INNER JOIN device_level dl ON dl.MACHINE_ID = o.device_id GROUP BY station_id, station_name",
      "expression_skeleton": "SELECT station_id, station_name, AVG(oee) oee, SUM(oee) sumOee, count(distinct(dl.MACHINE_ID)) machineNumber FROM OEE_CORE_INDEX_REPORT o INNER JOIN device_level dl ON dl.MACHINE_ID = o.device_id GROUP BY station_id, station_name",
      "param_examples": {},
      "param_types": {},
      "grain": "station",
      "tables": [
        "oee_core_index_report",
        "device_level"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "grain_unique": "grain=station；问题说『工位』才用，说『设备』改用 v2-06『设备级 OEE 趋势』",
      "user_questions": [
        "工位 OEE",
        "工位综合效率",
        "每个工位的 OEE 是多少"
      ],
      "source": "OeeCoreIndexReportMapper.selectSumShutdownExport",
      "confidence": "high"
    },
    {
      "metric": "OEE 自因损失按设备聚合",
      "synonyms": [
        "设备自因损失",
        "每台设备停机损失",
        "deviceSelfReason",
        "machineDowntimeLoss"
      ],
      "definition": "按 device_id 聚合 SUSPEN+MINOR_SHUTDOWN+HEAT_ENGINE+UNABLE_TO_CONNECT 各类损失秒数（含设备名）。注：所有损失字段单位都是秒。",
      "expression": "SELECT a.* , device_name FROM device d INNER join (SELECT device_id, sum(UNABLE_TO_CONNECT) UNABLE_TO_CONNECT, sum(MINOR_SHUTDOWN) MINOR_SHUTDOWN, sum(SUSPEN) SUSPEN, sum(HEAT_ENGINE) HEAT_ENGINE FROM OEE_CORE_INDEX_REPORT GROUP BY device_id) a on d.id = a.device_id",
      "expression_skeleton": "SELECT a.* , device_name FROM device d INNER join (SELECT device_id, sum(UNABLE_TO_CONNECT) UNABLE_TO_CONNECT, sum(MINOR_SHUTDOWN) MINOR_SHUTDOWN, sum(SUSPEN) SUSPEN, sum(HEAT_ENGINE) HEAT_ENGINE FROM OEE_CORE_INDEX_REPORT GROUP BY device_id) a on d.id = a.device_id",
      "param_examples": {},
      "param_types": {},
      "grain": "device",
      "tables": [
        "oee_core_index_report",
        "device"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "grain_unique": "grain=device + 4 类自因秒数；不要与 v2-10『OEE 损失原因分布』按日聚合混用",
      "user_questions": [
        "每台设备的停机损失",
        "设备自因损失分布",
        "哪些设备损失最多",
        "按设备看 OEE 损失"
      ],
      "source": "OeeCoreIndexReportMapper.selectByGroupByDeviceId",
      "confidence": "high"
    },
    {
      "metric": "OEE 损失原因分布",
      "synonyms": [
        "原因占比",
        "自因损失日趋势",
        "reasonDistribution",
        "selfReasonDaily"
      ],
      "definition": "按 start_time 当天聚合 SUSPEN/MINOR_SHUTDOWN/HEAT_ENGINE/UNABLE_TO_CONNECT 各类自因秒数；用于 OEE 自因归因分析。",
      "expression": "SELECT TO_CHAR(START_TIME,'YYYY-MM-DD') xAxial, sum(MINOR_SHUTDOWN) MINOR_SHUTDOWN, sum(SUSPEN) SUSPEN, sum(HEAT_ENGINE) HEAT_ENGINE, sum(UNABLE_TO_CONNECT) UNABLE_TO_CONNECT FROM OEE_CORE_INDEX_REPORT GROUP BY TO_CHAR(START_TIME,'YYYY-MM-DD')",
      "expression_skeleton": "SELECT TO_CHAR(START_TIME,'YYYY-MM-DD') xAxial, sum(MINOR_SHUTDOWN) MINOR_SHUTDOWN, sum(SUSPEN) SUSPEN, sum(HEAT_ENGINE) HEAT_ENGINE, sum(UNABLE_TO_CONNECT) UNABLE_TO_CONNECT FROM OEE_CORE_INDEX_REPORT GROUP BY TO_CHAR(START_TIME,'YYYY-MM-DD')",
      "param_examples": {},
      "param_types": {},
      "grain": "time",
      "tables": [
        "oee_core_index_report"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "grain_unique": "grain=time（按日聚合）；与 v2-09『按设备聚合』互补，按问题关键词选",
      "user_questions": [
        "OEE 损失原因分布",
        "每天自因损失",
        "各类停机原因占比",
        "OEE 自因分析"
      ],
      "source": "OeeCoreIndexReportMapper.selectBySelfReason",
      "confidence": "high"
    },
    {
      "metric": "Uptime 综合稼动率趋势",
      "synonyms": [
        "Uptime趋势",
        "稼动率趋势",
        "uptimeTrend",
        "utilizationTrend"
      ],
      "definition": "按 START_TIME 当天 + SHIFT_ID=2 计算 Uptime（区别于 OEE：Uptime 不含性能/合格率，只算时间稼动率）。",
      "expression": "SELECT TO_CHAR(START_TIME,'YYYY-MM-DD') YYYYMMDD, ROUND(CASE WHEN SUM(CASE WHEN TYPE = '1' AND SHIFT_ID = '2' THEN COALESCE(PLAN_RUN_TIME, 0) END) = 0 THEN 0 ELSE SUM(CASE WHEN TYPE = '1' AND SHIFT_ID = '2' THEN COALESCE(VALUABLE_TIME, 0) END) / SUM(CASE WHEN TYPE = '1' AND SHIFT_ID = '2' THEN COALESCE(PLAN_RUN_TIME, 0) END) END * 100, 2) UPTIME FROM OEE_CORE_INDEX_REPORT GROUP BY TO_CHAR(START_TIME,'YYYY-MM-DD')",
      "expression_skeleton": "SELECT TO_CHAR(START_TIME,'YYYY-MM-DD') YYYYMMDD, ROUND(CASE WHEN SUM(CASE WHEN TYPE = '1' AND SHIFT_ID = '2' THEN COALESCE(PLAN_RUN_TIME, 0) END) = 0 THEN 0 ELSE SUM(CASE WHEN TYPE = '1' AND SHIFT_ID = '2' THEN COALESCE(VALUABLE_TIME, 0) END) / SUM(CASE WHEN TYPE = '1' AND SHIFT_ID = '2' THEN COALESCE(PLAN_RUN_TIME, 0) END) END * 100, 2) UPTIME FROM OEE_CORE_INDEX_REPORT GROUP BY TO_CHAR(START_TIME,'YYYY-MM-DD')",
      "param_examples": {},
      "param_types": {},
      "grain": "time",
      "tables": [
        "oee_core_index_report"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "grain_unique": "区别于 OEE：Uptime=VALUABLE_TIME/PLAN_RUN_TIME×100（仅时间稼动率）；用户问『稼动率』才用此条",
      "user_questions": [
        "Uptime 趋势",
        "稼动率趋势",
        "时间稼动率走势"
      ],
      "source": "UptimeMapper.selectUptimeLine",
      "confidence": "medium"
    },
    {
      "metric": "未计划生产告警",
      "synonyms": [
        "无计划生产",
        "未排产却生产",
        "unplannedProduction",
        "noPlanProduction"
      ],
      "definition": "OEE_REPORT 中没有计划但实际生产的记录条数；用于识别计划缺失或调度异常。",
      "expression": "SELECT count(*) as num, 0 as plannedQuantity, '无计划生产' as unplannedProduction FROM OEE_REPORT WHERE start_time >= to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') and start_time < to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS')",
      "expression_skeleton": "SELECT count(*) as num, 0 as plannedQuantity, '无计划生产' as unplannedProduction FROM OEE_REPORT WHERE start_time >= to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') and start_time < to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS')",
      "param_examples": {
        "startDay": "'2026-08-01 00:00:00'",
        "endDay": "'2026-08-19 23:59:59'"
      },
      "param_types": {
        "startDay": "TIMESTAMP",
        "endDay": "TIMESTAMP"
      },
      "grain": "time",
      "tables": [
        "oee_report"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "grain_unique": "告警类指标，独立于其他产能类",
      "user_questions": [
        "未计划生产",
        "无计划却生产了",
        "哪些没排产还在跑"
      ],
      "source": "OeeReportMapper.outputLastProcess",
      "confidence": "medium"
    },
    {
      "metric": "工序级 OEE 报表",
      "synonyms": [
        "工序OEE",
        "processOeeReport",
        "每个工序的OEE"
      ],
      "definition": "按 PROCESS_ID + PROCESS_NAME 聚合 OEE，含 MACHINE_AMOUNT 设备台数；用于工序级 OEE 看板与瓶颈工序识别。",
      "expression": "SELECT FACTOTY_ID, FACTOTY_NAME, PROCESS_ID, PROCESS_NAME, count(distinct MACHINE_ID) MACHINE_AMOUNT, ROUND(CASE WHEN SUM(COALESCE(PLAN_RUN_TIME,0))=0 THEN 0 ELSE SUM(COALESCE(VALUABLE_TIME,0))/SUM(COALESCE(PLAN_RUN_TIME,0)) END * 100, 2) OEE FROM OEE_CORE_INDEX_REPORT where START_TIME >= to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') and START_TIME < to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') GROUP BY FACTOTY_ID, FACTOTY_NAME, PROCESS_ID, PROCESS_NAME",
      "expression_skeleton": "SELECT FACTOTY_ID, FACTOTY_NAME, PROCESS_ID, PROCESS_NAME, count(distinct MACHINE_ID) MACHINE_AMOUNT, ROUND(CASE WHEN SUM(COALESCE(PLAN_RUN_TIME,0))=0 THEN 0 ELSE SUM(COALESCE(VALUABLE_TIME,0))/SUM(COALESCE(PLAN_RUN_TIME,0)) END * 100, 2) OEE FROM OEE_CORE_INDEX_REPORT where START_TIME >= to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') and START_TIME < to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') GROUP BY FACTOTY_ID, FACTOTY_NAME, PROCESS_ID, PROCESS_NAME",
      "param_examples": {
        "startDate": "'2026-08-01 00:00:00'",
        "endDate": "'2026-08-19 23:59:59'"
      },
      "param_types": {
        "startDate": "TIMESTAMP",
        "endDate": "TIMESTAMP"
      },
      "grain": "project",
      "tables": [
        "oee_core_index_report",
        "work_station"
      ],
      "excluded_conditions": [
        "device.deleted = false"
      ],
      "grain_unique": "grain=project + 工序维度；问题说『工序』才用此条",
      "user_questions": [
        "工序 OEE",
        "每个工序的综合效率",
        "工序级 OEE 报表",
        "哪些工序 OEE 低"
      ],
      "source": "UptimeMapper.processList",
      "confidence": "high"
    }
  ]
}
""";
}
