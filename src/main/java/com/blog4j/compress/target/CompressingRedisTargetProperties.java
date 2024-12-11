package com.blog4j.compress.target;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompressingRedisTargetProperties {

    /*
        default values
        check application-[profile].yml
        targetCacheManagers, thresholdSize binding CompressingProperties
        redis host, port binding RedisDefaultProperties
     */

    private List<String> targetCacheManagers = new ArrayList<>();

    private long thresholdSize = 10;

    private String host = "127.0.0.1";

    private int port = 6379;

}
