package com.litedata.service;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record QueryPage(int pageNum,int pageSize) {
    public QueryPage {
        if(pageNum<1 || pageSize<1 || pageSize>1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"pageNum 须为正整数，pageSize 须在1到1000之间");
    }
    public static QueryPage parse(Map<String,String> params,int defaultSize) {
        return new QueryPage(number(params.get("pageNum"),1),number(params.get("pageSize"),defaultSize));
    }
    private static int number(String value,int defaultValue) {
        if(value==null) return defaultValue;
        try {
            if(!value.matches("[0-9]{1,10}")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch(NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"分页参数须为有效整数");
        }
    }
    public long offset() { return (pageNum-1L)*pageSize; }
}
