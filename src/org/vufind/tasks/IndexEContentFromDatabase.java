package org.vufind.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vufind.ConnectionProvider;
import org.vufind.config.ConfigFiller;
import org.vufind.config.DynamicConfig;
import org.vufind.config.sections.BasicConfigOptions;
import org.vufind.config.sections.EContentConfigOptions;
import org.vufind.config.sections.MarcConfigOptions;
import org.vufind.econtent.FreegalImporter;
import org.vufind.processors.IEContentProcessor;
import org.vufind.processors.IMarcRecordProcessor;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by jbannon on 7/3/14.
 */
public class IndexEContentFromDatabase {
    final static Logger logger = LoggerFactory.getLogger(ProcessMarc.class);

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                System.out
                        .println("Please enter the config file loc as the first param");
                System.exit(-1);
            }

            String configFolder = args[0];

            DynamicConfig config = new DynamicConfig();
            ConfigFiller.fill(config, Arrays.asList(BasicConfigOptions.values()), new File(configFolder));
            ConfigFiller.fill(config, Arrays.asList(EContentConfigOptions.values()), new File(configFolder));
            config.put(BasicConfigOptions.CONFIG_FOLDER, configFolder);

            IndexEContentFromDatabase task = new IndexEContentFromDatabase(config);
            task.run();
        } catch (Exception e) {
            logger.error("Unknown error", e);
        }
    }


    final DynamicConfig config;

    public IndexEContentFromDatabase(DynamicConfig config) {
        this.config = config;
    }

    private void run() {
        Connection econtentConn = ConnectionProvider.getConnection(config,
                ConnectionProvider.PrintOrEContent.E_CONTENT);
        try {
            List<IEContentProcessor> econtentProcessors = loadProcessors();

            PreparedStatement econtentRecordStatement = econtentConn
                    .prepareStatement("SELECT * FROM econtent_record WHERE status = 'active'");
            ResultSet allEContent = econtentRecordStatement.executeQuery();
            while (allEContent.next()) {
                for (IEContentProcessor econtentProcessor : econtentProcessors) {
                    //TODO we should really have an EContentRecord object instead of passing a ResultSet
                    econtentProcessor.processEContentRecord(allEContent);
                }
            }
        } catch (SQLException ex) {
            // handle any errors
            logger.error("Unable to load econtent records from database", ex);
        }

    }

    private List<IEContentProcessor> loadProcessors() {
        List<Class> processorClasses = (List<Class>)config.get(EContentConfigOptions.PROCESSORS);
        List<IEContentProcessor> processors = new ArrayList();

        for(Class processorClass: processorClasses) {
            Object instance = null;
            try {
                instance = processorClass.newInstance();
                if(instance instanceof IEContentProcessor) {
                    IEContentProcessor processor = (IEContentProcessor)instance;
                    processor.init(config);
                    processors.add(processor);
                }
            } catch (Exception e) {
                logger.error("Error loading EContentProcessor", e);
            }
        }

        return processors;
    }
}