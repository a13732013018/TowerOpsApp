
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

import java.util.ArrayList;
import java.util.List;

public class WorkOrderAdapter extends RecyclerView.Adapter<WorkOrderAdapter.VH> {

    private final List<WorkOrder> data = new ArrayList<>();

    public void setData(List<WorkOrder> list) {
        data.clear();
        data.addAll(list);
        notifyDataSetChanged();
    }

    /** 按 billsn 更新某行的状态列 */
    public void updateStatus(int rowHint, String billsn, String content) {
        // 快速路径
        if (rowHint >= 0 && rowHint < data.size()
                && billsn.equals(data.get(rowHint).billsn)) {
            data.get(rowHint).statusCol = content;
            notifyItemChanged(rowHint);
            return;
        }
        // 全表搜索
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
                 tvTimeDiff2, tvTimeDiff, tvStatus;

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
