package com.meituan.firefly.rx_testfirefly;

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.meituan.firefly.annotations.*;


public class MixStruct {
    
    @Field(id = 1, required = true, name = "id") public Integer id;
    
    @Field(id = -1, required = true, name = "uid") public Integer uid;
}