package com.myproject.S2dcms.dto.department;

public class DepartmentComplaintStatsResponse {
    private long total;
    private long pending;
    private long inProgress;
    private long replied;
    private long closed;

    public DepartmentComplaintStatsResponse() {
    }

    public DepartmentComplaintStatsResponse(long total, long pending, long inProgress, long replied, long closed) {
        this.total = total;
        this.pending = pending;
        this.inProgress = inProgress;
        this.replied = replied;
        this.closed = closed;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getPending() {
        return pending;
    }

    public void setPending(long pending) {
        this.pending = pending;
    }

    public long getInProgress() {
        return inProgress;
    }

    public void setInProgress(long inProgress) {
        this.inProgress = inProgress;
    }

    public long getReplied() {
        return replied;
    }

    public void setReplied(long replied) {
        this.replied = replied;
    }

    public long getClosed() {
        return closed;
    }

    public void setClosed(long closed) {
        this.closed = closed;
    }
}