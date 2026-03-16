package com.towerops.app.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.towerops.app.R;
import com.towerops.app.model.WorkOrder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class WorkOrderAdapter extends RecyclerView.Adapter<WorkOrderAdapter.VH> {

    /** 排序模式 */
    public enum SortMode {
        BILL_TIME_DESC,      // 工单历时 大→小（默认）
        BILL_TIME_ASC,       // 工单历时 小→大
        FEEDBACK_TIME_DESC,  // 反馈历时 大→小
        FEEDBACK_TIME_ASC,   // 反馈历时 小→大
        ALERT_TIME_DESC,     // 告警时间 最新→最旧
        ALERT_TIME_ASC,      // 告警时间 最旧→最新
        ALERT_STATUS_ALARM,  // 告警状态：告警中优先
        ALERT_STATUS_RECOVER // 告警状态：已恢复优先
    }

    private final List<WorkOrder> data = new ArrayList<>();
    private SortMode sortMode = SortMode.BILL_TIME_DESC; // 默认：工单历时从大到小

    /** 设置排序模式并立即刷新列表 */
    public void setSortMode(SortMode mode) {
        this.sortMode = mode;
        applySort();
        notifyDataSetChanged();
    }

    public SortMode getSortMode() { return sortMode; }

    public void setData(List<WorkOrder> list) {
        data.clear();
        data.addAll(list);
        applySort();
        notifyDataSetChanged();
    }

    /** 内部排序（不改原始数据顺序，只对 data 排序） */
    private void applySort() {
        switch (sortMode) {
            case BILL_TIME_DESC:
                // 工单历时（timeDiff2）从大到小
                Collections.sort(data, (a, b) -> b.timeDiff2 - a.timeDiff2);
                break;
            case BILL_TIME_ASC:
                // 工单历时（timeDiff2）从小到大
                Collections.sort(data, (a, b) -> a.timeDiff2 - b.timeDiff2);
                break;
            case FEEDBACK_TIME_DESC:
                // 反馈历时（timeDiff1）从大到小
                Collections.sort(data, (a, b) -> b.timeDiff1 - a.timeDiff1);
                break;
            case FEEDBACK_TIME_ASC:
                // 反馈历时（timeDiff1）从小到大
                Collections.sort(data, (a, b) -> a.timeDiff1 - b.timeDiff1);
                break;
            case ALERT_TIME_DESC:
                // 告警时间：最新的排最前
                Collections.sort(data, (a, b) -> compareAlertTime(b, a));
                break;
            case ALERT_TIME_ASC:
                // 告警时间：最旧的排最前
                Collections.sort(data, (a, b) -> compareAlertTime(a, b));
                break;
            case ALERT_STATUS_ALARM:
                // 告警中 优先（告警中=0，已恢复=1）
                Collections.sort(data, (a, b) -> alertStatusOrder(a) - alertStatusOrder(b));
                break;
            case ALERT_STATUS_RECOVER:
                // 已恢复 优先
                Collections.sort(data, (a, b) -> alertStatusOrder(b) - alertStatusOrder(a));
                break;
        }
    }

    /** 比较告警时间（解析为时间戳比较，格式不一致时降级为字符串比较，空值排末尾） */
    private static int compareAlertTime(WorkOrder a, WorkOrder b) {
        String ta = (a.alertTime == null) ? "" : a.alertTime.trim();
        String tb = (b.alertTime == null) ? "" : b.alertTime.trim();
        if (ta.isEmpty() && tb.isEmpty()) return 0;
        if (ta.isEmpty()) return 1;  // a为空排末尾
        if (tb.isEmpty()) return -1; // b为空排末尾
        long tsMsA = parseTimeToMillis(ta);
        long tsMsB = parseTimeToMillis(tb);
        // 解析成功就用时间戳比较，否则降级字符串字典序
        if (tsMsA > 0 && tsMsB > 0) {
            return Long.compare(tsMsA, tsMsB);
        }
        return ta.compareTo(tb);
    }

    /** 尝试把时间字符串解析为毫秒时间戳，失败返回 -1 */
    private static long parseTimeToMillis(String s) {
        if (s == null || s.isEmpty()) return -1;
        // 统一替换斜杠、T 为标准格式
        String norm = s.replace("/", "-").replace("T", " ").trim();
        // 截取到秒（19位）
        if (norm.length() > 19) norm = norm.substring(0, 19);
        String[] patterns = { "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd" };
        for (String fmt : patterns) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.getDefault());
                sdf.setLenient(false);
                Date d = sdf.parse(norm.substring(0, Math.min(norm.length(), fmt.length())));
                if (d != null) return d.getTime();
            } catch (Exception ignored) {}
        }
        return -1;
    }

    /** 告警状态排序权重：告警中=0, 已恢复=1 */
    private static int alertStatusOrder(WorkOrder wo) {
        return "告警中".equals(wo.alertStatus) ? 0 : 1;
    }

    /** 按 billsn 更新某行的状态列（排序后行位置可能变化，始终全表搜索）*/
    public void updateStatus(int rowHint, String billsn, String content) {
        for (int i = 0; i < data.size(); i++) {
            if (billsn.equals(data.get(i).billsn)) {
                data.get(i).statusCol = content;
                notifyItemChanged(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_work_order, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        WorkOrder wo = data.get(pos);
        h.tvIndex.setText(String.valueOf(wo.index));
        h.tvBillSn.setText(wo.billsn);
        h.tvTitle.setText(wo.stationname + "  " + wo.billtitle);
        h.tvAcceptOp.setText("接单：" + (wo.acceptOperator.isEmpty() ? "未接单" : wo.acceptOperator));
        h.tvCreateTime.setText(wo.createTime.length() > 16 ? wo.createTime.substring(0, 16) : wo.createTime);
        h.tvDealInfo.setText(wo.dealInfo.isEmpty() ? "" : "处理：" + wo.dealInfo);
        // 工单历时（从创建到现在）
        h.tvTimeDiff2.setText("工单历时：" + formatMinutes(wo.timeDiff2));
        // 距上次反馈：显示具体时间 + 经过分钟数，两段信息都展示
        if (wo.lastOperateTime != null && !wo.lastOperateTime.isEmpty()) {
            // 截取时间到分钟（最多16位），如 "2026-03-15 14:30"
            String showTime = wo.lastOperateTime.length() > 16
                    ? wo.lastOperateTime.substring(0, 16) : wo.lastOperateTime;
            h.tvTimeDiff.setText("上次反馈：" + showTime + "（" + formatMinutes(wo.timeDiff1) + "前）");
        } else {
            h.tvTimeDiff.setText("上次反馈：尚未反馈");
        }
        // 告警发生时间
        if (wo.alertTime != null && !wo.alertTime.isEmpty()) {
            String showAt = wo.alertTime.length() > 16 ? wo.alertTime.substring(0, 16) : wo.alertTime;
            h.tvAlertTime.setText("告警时间：" + showAt);
            h.tvAlertTime.setVisibility(View.VISIBLE);
        } else {
            h.tvAlertTime.setText("");
            h.tvAlertTime.setVisibility(View.GONE);
        }
        h.tvStatus.setText(wo.statusCol == null ? "" : wo.statusCol);

        // 告警状态颜色
        if ("告警中".equals(wo.alertStatus)) {
            h.tvAlertStatus.setText("⚡告警中");
            h.tvAlertStatus.setTextColor(Color.parseColor("#ff6b35"));
        } else {
            h.tvAlertStatus.setText("✓已恢复");
            h.tvAlertStatus.setTextColor(Color.parseColor("#40c080"));
        }

        // 状态列颜色
        String sc = wo.statusCol == null ? "" : wo.statusCol;
        if (sc.contains("成功") || sc.contains("完毕")) {
            h.tvStatus.setTextColor(Color.parseColor("#40c080"));
        } else if (sc.contains("失败") || sc.contains("异常") || sc.contains("拦截")) {
            h.tvStatus.setTextColor(Color.parseColor("#e94560"));
        } else {
            h.tvStatus.setTextColor(Color.parseColor("#e0c060"));
        }
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvIndex, tvBillSn, tvAlertStatus, tvTitle,
                 tvAcceptOp, tvCreateTime, tvDealInfo,
                 tvTimeDiff2, tvTimeDiff, tvAlertTime, tvStatus;

        VH(View v) {
            super(v);
            tvIndex       = v.findViewById(R.id.tvIndex);
            tvBillSn      = v.findViewById(R.id.tvBillSn);
            tvAlertStatus = v.findViewById(R.id.tvAlertStatus);
            tvTitle       = v.findViewById(R.id.tvTitle);
            tvAcceptOp    = v.findViewById(R.id.tvAcceptOp);
            tvCreateTime  = v.findViewById(R.id.tvCreateTime);
            tvDealInfo    = v.findViewById(R.id.tvDealInfo);
            tvTimeDiff2   = v.findViewById(R.id.tvTimeDiff2);
            tvTimeDiff    = v.findViewById(R.id.tvTimeDiff);
            tvAlertTime   = v.findViewById(R.id.tvAlertTime);
            tvStatus      = v.findViewById(R.id.tvStatus);
        }
    }

    /** 将分钟数格式化为可读字符串，如 125分钟 → 2小时5分钟 */
    private static String formatMinutes(int minutes) {
        if (minutes <= 0) return "0分钟";
        if (minutes < 60) return minutes + "分钟";
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? h + "小时" : h + "小时" + m + "分钟";
    }
}

