package com.cufe.deepweb.crawler.branch;

import com.cufe.deepweb.common.Utils;
import com.cufe.deepweb.crawler.Constant;
import com.cufe.deepweb.common.orm.model.Current;
import com.cufe.deepweb.algorithm.AlgorithmBase;
import com.cufe.deepweb.common.orm.Orm;
import com.cufe.deepweb.crawler.service.InfoLinkService;
import com.cufe.deepweb.crawler.service.QueryLinkService;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sql2o.Connection;
import org.sql2o.Sql2o;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * main scheduler thread, be responsible for produce queryLinks and manage crawler status
 */
public final class Scheduler extends Thread{
    private static Logger logger = LoggerFactory.getLogger(Scheduler.class);
    private AlgorithmBase algo;
    private QueryLinkService queryLinkService;
    private InfoLinkService infoLinkService;
    private BlockingDeque msgQueue;
    private Sql2o sql2o;
    private ThreadFactory threadFactory;
    public Scheduler(AlgorithmBase algo, QueryLinkService queryLinkService, InfoLinkService infoLinkService, BlockingDeque msgQueue){
        super("producer_thread");
        this.algo = algo;
        this.queryLinkService = queryLinkService;
        this.infoLinkService = infoLinkService;
        this.msgQueue = msgQueue;
        this.sql2o = Orm.getSql2o();

        //configure the download thread pool's threadFactory
        threadFactory = new ThreadFactoryBuilder()
            .setUncaughtExceptionHandler((thread, exception) -> {
                logger.error("error happen in consume thread", exception);
            })
            .setNameFormat("consume_thread_%s")
            .build();

    }
    @Override
    public void run() {
        logger.info("start the produce thread");

        while (this.isContinue()) {
            //status1: deal with the database initialization
            try (Connection conn = sql2o.open()) {
                String sql;
                Constant.round++;
                //update the row corresponding to the webID in database's current table
                sql = "update current set M1status =:M1status, M2status =:M2status, M3status =:M3status, M4status =:M4status, round =:round where webId =:webID";
                conn.createQuery(sql)
                        .addParameter("M1status", Constant.CURRENT_STATUS_INACTIVE)
                        .addParameter("M2status",Constant.CURRENT_STATUS_INACTIVE)
                        .addParameter("M3status",Constant.CURRENT_STATUS_INACTIVE)
                        .addParameter("M4status",Constant.CURRENT_STATUS_INACTIVE)
                        .addParameter("round", Constant.round + "")
                        .addParameter("webID",Constant.webSite.getWebId())
                        .executeUpdate();

                this.fixStatus(0,1);
                logger.info("start the M1status");

                sql = "insert into status(webId,round,type,fLinkNum,sLinkNum)" +
                    "values(:webID,:round,:type,:fLinkNum,:sLinkNum)";
                conn.createQuery(sql)
                    .addParameter("webID", Constant.webSite.getWebId())
                    .addParameter("round", Constant.round + "")
                    .addParameter("type", Constant.STATUS_TYPE_INFO)
                    .addParameter("fLinkNum", 0)
                    .addParameter("sLinkNum", 0)
                    .executeUpdate();
                conn.createQuery(sql)
                    .addParameter("webID", Constant.webSite.getWebId())
                    .addParameter("round", Constant.round + "")
                    .addParameter("type", Constant.STATUS_TYPE_QUERY)
                    .addParameter("fLinkNum", 0)
                    .addParameter("sLinkNum", 0)
                    .executeUpdate();

            }


            this.fixStatus(1,2);
            logger.info("start the M2status");
            //status2: generate the query term
            String curQuery = algo.getNextQuery();
            logger.info("this turn's query is {}",curQuery);


            this.fixStatus(2,3);
            logger.info("start the M3status");
            //status3: get all the queryLinks
            QueryLinkService.QueryLinks queryLinks = queryLinkService.getQueryLinks(curQuery);


            this.fixStatus(3,4);
            logger.info("start the M4status");

            //only when query link number is bigger than zero, it's necessary to use the thread pool
            if (queryLinks.getPageNum() > 0) {
                ThreadPoolExecutor threadPool = new ThreadPoolExecutor(
                        Constant.webSite.getThreadNum(),
                        Constant.webSite.getThreadNum(),
                        0,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingDeque<>(),//set the size of thread queue to infinity
                        threadFactory
                );
                for (int i = 0 ; i < Constant.webSite.getThreadNum() ; i++) {
                    threadPool.execute(() -> {
                        while (true) {


                            String link = (String)msgQueue.poll();//if here is not null, this is a info link
                            if (link != null) {
                                this.consumeInfoLink(link);
                                continue;
                            }

                            //if can't get data from message queue, go to get query link from queryLink's generator
                            link = queryLinks.next();
                            if (link != null && queryLinkService.isQueryLink(link)) {
                                this.consumeQueryLink(link);
                                continue;
                            }


                            //if can't get info link from message queue and can't get query link from generator,
                            //the thread exit
                            //maybe in other thread would create new info links and push into message queue,
                            //but these message in queue would be consumed by their create thread
                            logger.info("can't get query link, total page num is {}， current counter is {}", queryLinks.getPageNum(), queryLinks.getCounter());
                            break;
                        }
                        queryLinkService.clearThreadResource();
                    });
                }
                threadPool.shutdown();

                //loop here until all the thread in thread pool exit
                int stopCount = 3;//a flag to indicate whether to force stop the thread pool
                while (true) {
                    try {
                        //most of the situation, the thread pool would close after the following block, and jump out the while loop
                        if (threadPool.awaitTermination(Constant.webSite.getThreadNum(), TimeUnit.SECONDS)) {
                            break;
                        }

                        //but sometimes some thread would block in the net IO and wouldn't wake up
                        //I also don't know why, but it did exist
                        //so need to force close the thread pool in the following clauses
                        if (threadPool.getActiveCount() < threadPool.getCorePoolSize() / 2) {
                            logger.info("activeCount:{}, poolSize:{}", threadPool.getActiveCount(), threadPool.getCorePoolSize());
                            stopCount--;
                            if(stopCount <= 0) {
                                threadPool.shutdownNow();
                                break;
                            }
                        }
                    } catch (InterruptedException ex) {
                        logger.error("interrupted when wait for thread pool");
                    }
                }
            }


            try (Connection conn = sql2o.open()) {
                String sql = null;

                //update the last round's fLinkNum and sLinkNum in database's status table
                sql = "update status set fLinkNum =:fLinkNum, sLinkNum =:sLinkNum where webId =:webID and type =:type and round=:round";

                //update the last round's fLinkNum and sLinkNum in database's status table
                int fLinkNum = queryLinkService.getFailedLinkNum();
                int sLinkNum = queryLinkService.getTotalLinkNum() - fLinkNum;
                conn.createQuery(sql)
                        .addParameter("fLinkNum", fLinkNum)
                        .addParameter("sLinkNum", sLinkNum)
                        .addParameter("webID", Constant.webSite.getWebId())
                        .addParameter("type", Constant.STATUS_TYPE_QUERY)
                        .addParameter("round", Constant.round + "")
                        .executeUpdate();

                fLinkNum = infoLinkService.getFailedLinkNum();
                sLinkNum = infoLinkService.getTotalLinkNum() - fLinkNum;
                conn.createQuery(sql)
                        .addParameter("fLinkNum", fLinkNum)
                        .addParameter("sLinkNum", sLinkNum)
                        .addParameter("webID", Constant.webSite.getWebId())
                        .addParameter("type", Constant.STATUS_TYPE_INFO)
                        .addParameter("round", Constant.round + "")
                        .executeUpdate();

                //update current table's SampleData_sum by adding sLinkNum to it
                sql = "update current set SampleData_sum = SampleData_sum +:sLinkNum where webId =:webID";
                conn.createQuery(sql)
                        .addParameter("sLinkNum", sLinkNum)
                        .addParameter("webID", Constant.webSite.getWebId())
                        .executeUpdate();

            }

            this.fixStatus(4,0);
        }
        System.exit(0);
    }

    /**
     * detect whether to continue to crawl
     * detect rule：the successful download number of  latest 10 round is lower than 10,
     * if true , return false and stop to crawl
     * @return
     */
    private boolean isContinue() {
        List<Integer> sLinkNumList = null;
        try (Connection conn = sql2o.open()) {
            //get the latest 10 round's successful download number
            String sql = "select sLinkNum from status where webId=:webID and type=:type order by statusId desc limit 10";
            sLinkNumList = conn.createQuery(sql).addParameter("webID", Constant.webSite.getWebId())
                    .addParameter("type",Constant.STATUS_TYPE_INFO)
                    .executeScalarList(Integer.class);
        }
        int totalNum = 0;//sum all the num
        if (!sLinkNumList.isEmpty()) {
            for (Integer each : sLinkNumList) {
                totalNum += each;
            }
        }
        if (sLinkNumList.size() == 10 && totalNum < 10) {
            return false;
        }
        return true;
    }

    /**
     * change the current status of current table
     * here the performance is not the key point, so don't care the number of sql to be executed
     * @param pre 1-4
     * @param cur 1-4
     */
    private void fixStatus(int pre, int cur) {
        try (Connection conn = sql2o.open()) {
            if (1 <= pre && pre <= 4) {
                String preStr = "M"+pre+"status";
                //only change the status value here
                String sql = "update current set " + preStr +" =:MpreStatus, round=:round where webId =:webID";
                conn.createQuery(sql)
                        .addParameter("MpreStatus",Constant.CURRENT_STATUS_DONE)
                        .addParameter("round", Constant.round+"")
                        .addParameter("webID", Constant.webSite.getWebId())
                        .executeUpdate();
            }
            if (1 <= cur && cur <= 4) {
                String curStr = "M"+cur+"status";
                String sql = "update current set " + curStr +" =:McurStatus, round=:round where webId =:webID";
                conn.createQuery(sql)
                        .addParameter("McurStatus",Constant.CURRENT_STATUS_ACTIVE)
                        .addParameter("round", Constant.round+"")
                        .addParameter("webID", Constant.webSite.getWebId())
                        .executeUpdate();
            }

            //get the newest value from current table
            Constant.current = conn.createQuery("select * from current where webId=:webID")
                    .addParameter("webID",Constant.webSite.getWebId())
                    .executeAndFetchFirst(Current.class);
        }
    }

    /**
     * consume query link
     * get info links from the page corresponding to the query link, and restore them into message queue
     * @param queryLink
     */
    private void consumeQueryLink(String queryLink) {
        List<String> infoLinks = queryLinkService.getInfoLinks(queryLink);
        if (infoLinks.size() == 0) return;
        logger.trace("consume query link {}, get info link num {}", queryLink, infoLinks.size());
        infoLinks.forEach(infoLink -> {
            //if can't push info links into message queue
            //maybe because the message queue is full(this situation is hard to happen, just possible)
            //directly consume the info links in current thread
            if (!msgQueue.offer(infoLink)) {
                consumeInfoLink(infoLink);
            }
        });
    }

    /**
     * consume info link
     * download the page corresponding to the info link into directory, and build the page content into index
     * @param infoLink
     */
    private void consumeInfoLink(String infoLink) {
        logger.trace("consume info link {}", infoLink);
        infoLinkService.downloadAndIndex(infoLink, infoLinkService.getFileAddr(infoLink));
    }

}
