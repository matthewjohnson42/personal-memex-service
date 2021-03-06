package com.matthewjohnson42.personalMemexService.logic.service;

import com.matthewjohnson42.personalMemexService.data.DtoEntityConverter;
import com.matthewjohnson42.personalMemexService.data.DtoForEntity;
import com.matthewjohnson42.personalMemexService.data.Entity;
import com.matthewjohnson42.personalMemexService.data.converter.RawTextESConverter;
import com.matthewjohnson42.personalMemexService.data.converter.RawTextMongoConverter;
import com.matthewjohnson42.personalMemexService.data.dto.RawTextDto;
import com.matthewjohnson42.personalMemexService.data.elasticsearch.entity.RawTextES;
import com.matthewjohnson42.personalMemexService.data.elasticsearch.repository.ElasticRestTemplate;
import com.matthewjohnson42.personalMemexService.data.elasticsearch.repository.RawTextESRestTemplate;
import com.matthewjohnson42.personalMemexService.data.mongo.entity.RawTextMongo;
import com.matthewjohnson42.personalMemexService.data.mongo.repository.RawTextMongoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DataTransferService implements ApplicationContextAware {

    ApplicationContext applicationContext;

    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public void transferRawTextToES() {
        new MongoToEsTranferrer<String, RawTextDto, RawTextMongo, RawTextES>(
                applicationContext.getBean(RawTextMongoRepo.class),
                applicationContext.getBean(RawTextMongoConverter.class),
                applicationContext.getBean(RawTextESRestTemplate.class),
                applicationContext.getBean(RawTextESConverter.class),
                1000
        ).run();
    }

    /**
     * @param <ID> the ID type of the data being transferred
     * @param <D> the DTO type of the data being transferred
     * @param <M> the Mongo Entity type of the data being transferred
     * @param <E> the Elasticsearch Entity type of the data being transferred
     */
    private class MongoToEsTranferrer<ID, D extends DtoForEntity<ID>, M extends Entity<ID>, E extends Entity<ID>> {
        Logger logger = LoggerFactory.getLogger(MongoToEsTranferrer.class);
        private MongoRepository<M, String> mongoRepository;
        private DtoEntityConverter<ID, D, M> mongoDtoConverter;
        private ElasticRestTemplate<ID, E> elasticRestTemplate;
        private DtoEntityConverter<ID, D, E> elasticsearchConverter;
        private int batchSize;

        /**
         *
         * @param mongoRepository
         * @param mongoDtoConverter
         * @param elasticRestTemplate
         * @param elasticsearchConverter
         * @param batchSize
         */
        public MongoToEsTranferrer(MongoRepository mongoRepository,
                                   DtoEntityConverter mongoDtoConverter,
                                   ElasticRestTemplate elasticRestTemplate,
                                   DtoEntityConverter elasticsearchConverter,
                                   int batchSize) {
            this.mongoRepository = mongoRepository;
            this.elasticRestTemplate = elasticRestTemplate;
            this.mongoDtoConverter = mongoDtoConverter;
            this.elasticsearchConverter = elasticsearchConverter;
            this.batchSize = batchSize;
        }

        public void run() {
            int mongoRequestRetryLimit = 10;
            long totalDocuments = mongoRepository.count();
            long totalPages = totalDocuments / batchSize;
            List<Long> pageNumbers = new ArrayList<>();
            for (long i = 0; i <= totalPages; i++) {
                pageNumbers.add(i);
            }
            pageNumbers.stream().forEach(pageNumber -> {
                boolean pageRetrieved = false;
                int retryCount = 0;
                Page<M> mongoPage = null;
                while (!pageRetrieved && retryCount < mongoRequestRetryLimit) {
                    try {
                        mongoPage = mongoRepository.findAll(PageRequest.of(pageNumber.intValue(), batchSize));
                        pageRetrieved = true;
                    } catch (Exception e0) {
                        logger.info("Error when attempting to retrieve from Mongo", e0);
                        try {
                            Thread.sleep(1000);
                        } catch (Exception e1) {
                        }
                    }
                    retryCount++;
                }
                List<E> esEntities = new ArrayList<>();
                if (mongoPage != null) {
                    mongoPage.getContent().forEach(mongoEntity -> {
                        D dto = mongoDtoConverter.convertEntity(mongoEntity);
                        E esEntity = elasticsearchConverter.convertDto(dto);
                        esEntities.add(esEntity);
                    });
                }
                esEntities.stream().forEach(esEntity -> {
                    elasticRestTemplate.save(esEntity);
                });
            });
        }
    }

}
