package com.towerops.app.model;

/**
 * 工单数据模型 —— 对应主控线程从 billList 解析出的每条记录
 */
public class WorkOrder {
    public int    index;           // 序号
    public String billsn;          // 工单号
    public String createTime;      // 创建时间
    public String stationname;     // 站点名
    public String billtitle;       // 工单标题
    public String replyTime;       // 回单期限
    public int    timeDiff2;       // 创建至今分钟数
    public String alertStatus;     // 告警中 / 已恢复
    public String dealInfo;        // 最新处理描述
    public String lastOperateTime; // 最近操作时间
    public int    timeDiff1;       // 最近操作至今分钟数（或创建至今）
    public String acceptOperator;  // 接单人
    public String statusCol;       // 第12列状态显示（动态刷新）
    public String alertTime;       // 最早告警发生时间（从告警列表解析）

    public String billid;
    public String taskId;
}
