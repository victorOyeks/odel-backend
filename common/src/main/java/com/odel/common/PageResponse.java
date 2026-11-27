package com.odel.common;

import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(List<T> items, int page, int size, long total) {
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> map) {
        return new PageResponse<>(page.getContent().stream().map(map).toList(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
