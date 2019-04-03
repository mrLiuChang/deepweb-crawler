package com.cufe.deepweb.crawler.branch;

import com.cufe.deepweb.algorithm.AlgorithmBase;
import com.cufe.deepweb.crawler.service.infos.InfoLinkService;
import com.cufe.deepweb.crawler.service.querys.JsonBaseQueryLinkService;
import com.cufe.deepweb.crawler.service.querys.QueryLinkService;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;

public class JsonBaseScheduler extends Scheduler {
    private JsonBaseQueryLinkService queryLinkService;
    private InfoLinkService infoLinkService;
    private QueryLinkService.QueryLinks queryLinks;

    public JsonBaseScheduler(AlgorithmBase alg, JsonBaseQueryLinkService queryLinkService, InfoLinkService infoLinkService, BlockingDeque msgQueue) {
        super(alg, queryLinkService, infoLinkService, msgQueue);
        this.queryLinkService = queryLinkService;
        this.infoLinkService = infoLinkService;
    }
    @Override
    protected void status3(String query) {
        queryLinks = queryLinkService.getQueryLinks(query);
    }

    @Override
    protected ThreadPoolExecutor status4() {
        return null;
    }
}