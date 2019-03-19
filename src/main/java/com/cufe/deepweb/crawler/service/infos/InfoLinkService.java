package com.cufe.deepweb.crawler.service.infos;

import com.cufe.deepweb.common.http.client.resp.RespContent;
import com.cufe.deepweb.common.http.client.resp.RespStreamContent;
import com.cufe.deepweb.common.http.client.resp.RespStringContent;
import com.cufe.deepweb.crawler.Constant;
import com.cufe.deepweb.common.Utils;
import com.cufe.deepweb.common.http.client.CusHttpClient;
import com.cufe.deepweb.common.index.IndexClient;
import com.cufe.deepweb.crawler.service.LinkService;
import com.cufe.deepweb.crawler.service.infos.info.Info;
import org.apache.commons.lang3.StringUtils;
import org.htmlcleaner.CleanerProperties;
import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.TagNode;
import org.htmlcleaner.XPatherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * the service used to deal with info links
 * the implementation must be thread-safe
 */
public class InfoLinkService extends LinkService {
    private final static Logger logger = LoggerFactory.getLogger(InfoLinkService.class);
    private CusHttpClient httpClient;
    private IndexClient indexClient;
    /**
     * html tag parser, thread-safe
     */
    private HtmlCleaner htmlCleaner;
    /**
     * used to compute the downloaded document number in current round
     */
    private AtomicInteger count;
    public InfoLinkService(CusHttpClient httpClient, IndexClient indexClient) {
        this.httpClient = httpClient;
        this.indexClient = indexClient;
        this.htmlCleaner = new HtmlCleaner();
        CleanerProperties properties = this.htmlCleaner.getProperties();
        properties.setOmitComments(true);
        count = new AtomicInteger(0);
    }

    private Map<String, String> getFieldContentMap(String content) {
        Map<String, String> fieldContentMap = new HashMap<>();
        TagNode root = null;
        try {
            root = htmlCleaner.clean(content);//first to clean the html content
        } catch (Exception ex) {
            logger.error("exception happen when parse html content", ex);
            return Collections.emptyMap();
        }
        if (root == null) {
            return Collections.emptyMap();
        }
        for (Map.Entry<String, String> pattern : Constant.patternMap.entrySet()) {
            try {
                Object[] objects = root.evaluateXPath(pattern.getValue());
                List<String> strings = new ArrayList<>();
                if (objects != null && objects.length != 0) {//if can find data by the xpath
                    for (Object o : objects) {
                        TagNode node = (TagNode)o;
                        strings.add(node.getText().toString());
                    }
                    fieldContentMap.put(pattern.getKey(), StringUtils.join(strings,"\t"));
                }
            } catch (XPatherException ex) {
                logger.error("XpatherException happen when evaluate XPath for field " + pattern.getKey(), ex);
            }
        }


        //if //body can evaluate the content from HTML, just build the HTML content into fulltext field
        if (StringUtils.isBlank(fieldContentMap.get(Constant.FT_INDEX_FIELD))) {
            fieldContentMap.put(Constant.FT_INDEX_FIELD, content);
        }
        return fieldContentMap;
    }
    private String getFileAddr(String link, boolean generateFileName) {
        Path p = Paths.get(Constant.webSite.getWorkFile(),Constant.HTML_ADDR,Constant.current.getRound());
        File f = p.toFile();
        String newFilePath = null;
        //if the path of f no exist, create it
        if (!f.exists()) {
            f.mkdirs();
        }
        if (generateFileName) {
            String ext = ".html";//the default extension is .html
            if (link.contains(".")) {
                ext = link.substring(link.lastIndexOf("."));

                //if the document's extension is not in the range of this crawler's define, just change it to html
                if (!Constant.docTypes.contains(ext)) {
                    ext = ".html";
                }
            }
            newFilePath = p.resolve(count.getAndIncrement() + ext).toString();
        } else {
            newFilePath = p.resolve(link).toString();
        }

        totalLinkNum++;
        return newFilePath;
    }

    /**
     * download the target document and build into index
     * @param info
     */
    public void  downloadAndIndex(Info info) {
        RespContent content = httpClient.getContent(info.getUrl());
        Map<String ,String> map = info.getPayload() == null ? new HashMap<>() : info.getPayload();
        //save the document into directory if the return value contains a string
        //or save the attachment into a file if the return value contains an inputStream
        if (content instanceof RespStringContent) {//if the target document get successfully
            RespStringContent respStringContent = (RespStringContent) content;
            try {
                Utils.save2File(respStringContent.getContent(), getFileAddr(info.getUrl(), true));
            } catch (IOException ex) {
                logger.error("IOException in save content to file", ex);
            }
            map.putAll(getFieldContentMap((respStringContent.getContent())));
        } else if (content instanceof RespStringContent) {
            RespStreamContent respStreamContent = (RespStreamContent) content;
            try {
                Utils.save2File(respStreamContent.getStream(), getFileAddr(respStreamContent.getFileName(), false));
            } catch (IOException ex) {
                logger.error("IOException in save content to file", ex);
            }

        } else {
            failedLinkNum++;
        }
        indexClient.addDocument(map);
    }

    @Override
    public void clearThreadResource() {

    }
}