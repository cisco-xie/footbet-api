package com.example.demo.core.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageResult<T> {
    /**
     * 当前页数据
     */
    private List<T> records;

    /**
     * 总记录数
     */
    private Long total;

    /**
     * 当前页码
     */
    private Integer pageNum;

    /**
     * 每页大小
     */
    private Integer pageSize;

    /**
     * 总页数
     */
    public Long getPages() {
        if (pageSize == null || pageSize == 0) {
            return 0L;
        }
        return (total + pageSize - 1) / pageSize;
    }

    /**
     * 是否有上一页
     */
    public Boolean getHasPrevious() {
        return pageNum != null && pageNum > 1;
    }

    /**
     * 是否有下一页
     */
    public Boolean getHasNext() {
        return pageNum != null && getPages() != null && pageNum < getPages();
    }
}
