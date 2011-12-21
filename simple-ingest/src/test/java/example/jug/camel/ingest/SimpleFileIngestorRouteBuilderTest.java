package example.jug.camel.ingest;

import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.datatype.DatatypeFactory;

import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.model.RouteDefinition;
import org.apache.commons.io.FileUtils;
import org.example.model.AggregateRecordType;
import org.example.model.ObjectFactory;
import org.example.model.RecordType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import example.jug.camel.logic.NonRecoverableExternalServiceException;
import example.jug.camel.logic.RecoverableExternalServiceException;
import example.jug.camel.model.Record;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration
public class SimpleFileIngestorRouteBuilderTest {
	
	@Autowired
	private SimpleFileIngestorRouteBuilder builder;
	
	private File pollingFolder;
	private File doneFolder;
	private File failedFolder;
	
	@EndpointInject(uri = "mock:output")
	private MockEndpoint output;
	
	@Produce()
    private ProducerTemplate trigger;
	
	@Autowired
	private CamelContext context;
	
	private JAXBContext jaxbContext;
	
	@Before
    public void setup() throws Exception {
		
		// Polling folder setup
        pollingFolder = new File(builder.getSourceDirPath());
        FileUtils.deleteDirectory(pollingFolder);
        pollingFolder.mkdirs();
        
        // Done folder setup
        doneFolder = new File(pollingFolder, builder.getDoneDirPath());
        FileUtils.deleteDirectory(doneFolder);
        doneFolder.mkdirs();
        
        // Failed folder setup
        failedFolder = new File(pollingFolder, builder.getFailDirPath());
        FileUtils.deleteDirectory(failedFolder);
        failedFolder.mkdirs();
        
        jaxbContext = JAXBContext.newInstance(ObjectFactory.class);
    }
	
	@After
	public void teardown() throws Exception {
		output.reset();
	}
	
	@Test
	@DirtiesContext
	public void testPositive() throws Exception {
		
		DatatypeFactory dtf = DatatypeFactory.newInstance();
        
		Set<String> expectedIds = new HashSet<String>();
        AggregateRecordType agt = new AggregateRecordType();
        agt.setDate(dtf.newXMLGregorianCalendar(new GregorianCalendar()));
        
        output.setExpectedMessageCount(10);
        output.setResultWaitTime(15000);
        
        for (int i = 0; i < 10; i++) {
        	RecordType recordType = new RecordType();
        	recordType.setId(String.valueOf(i));
        	recordType.setDate(dtf.newXMLGregorianCalendar(new GregorianCalendar()));
        	recordType.setDescription("Record number: " + i);
        	agt.getRecord().add(recordType);
        	expectedIds.add(String.valueOf(i));
        }
        
        createAndMoveFile(agt);
        
        output.assertIsSatisfied();
        validateFileMove(false);
        
        for (Exchange exchange : output.getReceivedExchanges()) {
        	assertTrue(expectedIds.remove(exchange.getIn().getBody(Record.class).getId()));
        }
        
        assertTrue(expectedIds.isEmpty());
	}
	
	@Test
	@DirtiesContext
	public void testInvalidSchema() throws Exception {
		// not really atomic, but it works for tests
        FileUtils.moveFile(
        		new File("./target/test-classes/example/jug/camel/ingest/"
        				+ "SimpleFileIngestorRouteBuilderTest.testInvalidSchema.xml"),
        		new File(pollingFolder, "test.xml"));
        
        validateFileMove(true);
	}
	
	@Test
	@DirtiesContext
    public void testDuplicates() throws Exception {
        
	    DatatypeFactory dtf = DatatypeFactory.newInstance();

	    output.setExpectedMessageCount(1);
	    output.setResultWaitTime(3000l);

        RecordType recordType = new RecordType();
        recordType.setId("1");
        recordType.setDate(dtf.newXMLGregorianCalendar(new GregorianCalendar()));
        recordType.setDescription("Record number: 1");

        trigger.sendBody(
                SimpleFileIngestorRouteBuilder.HANDLE_RECORD_ROUTE_ENDPOINT_URI,
                marshallToXml(recordType));
        
        trigger.sendBody(
                SimpleFileIngestorRouteBuilder.HANDLE_RECORD_ROUTE_ENDPOINT_URI,
                marshallToXml(recordType));

        output.assertIsSatisfied();
    }
	
	@Test
    @DirtiesContext
    public void testTerminalJdbcFailure() throws Exception {
	    
	    configureJdbcFailure(3);

        DatatypeFactory dtf = DatatypeFactory.newInstance();

        Set<String> expectedIds = new HashSet<String>();

        output.setExpectedMessageCount(9);
        output.setResultWaitTime(12000l);

        for (int i = 0; i < 10; i++) {
            RecordType recordType = new RecordType();
            recordType.setId(String.valueOf(i));
            recordType.setDate(dtf.newXMLGregorianCalendar(new GregorianCalendar()));
            recordType.setDescription("Record number: " + i);

            if (i != 1) {
                expectedIds.add(String.valueOf(i));
            }

            try {
                trigger.sendBody(
                        SimpleFileIngestorRouteBuilder.HANDLE_RECORD_ROUTE_ENDPOINT_URI,
                        marshallToXml(recordType));
            } catch (CamelExecutionException e) {
                assertEquals("1", recordType.getId());
            }
        }

        output.assertIsSatisfied();

        for (Exchange exchange : output.getReceivedExchanges()) {
            assertTrue(expectedIds.remove(exchange.getIn()
                    .getBody(Record.class).getId()));
        }

        assertTrue(expectedIds.isEmpty());
    }

    @Test
    @DirtiesContext
    public void testNonTerminalJdbcFailure() throws Exception {

        configureJdbcFailure(1);

        DatatypeFactory dtf = DatatypeFactory.newInstance();

        Set<String> expectedIds = new HashSet<String>();

        output.setExpectedMessageCount(10);
        output.setResultWaitTime(12000l);

        for (int i = 0; i < 10; i++) {
            RecordType recordType = new RecordType();
            recordType.setId(String.valueOf(i));
            recordType.setDate(dtf
                    .newXMLGregorianCalendar(new GregorianCalendar()));
            recordType.setDescription("Record number: " + i);
            expectedIds.add(String.valueOf(i));

            try {
                trigger.sendBody(
                        SimpleFileIngestorRouteBuilder.HANDLE_RECORD_ROUTE_ENDPOINT_URI,
                        marshallToXml(recordType));
            } catch (CamelExecutionException e) {
                assertEquals("1", recordType.getId());
            }
        }

        output.assertIsSatisfied();

        for (Exchange exchange : output.getReceivedExchanges()) {
            assertTrue(expectedIds.remove(exchange.getIn()
                    .getBody(Record.class).getId()));
        }

        assertTrue(expectedIds.isEmpty());
    }

    @Test
    @DirtiesContext
    public void testRecoverableExternalServiceException() throws Exception {
        configureProcessRecordFailure(1, true);
        
        DatatypeFactory dtf = DatatypeFactory.newInstance();

        output.setExpectedMessageCount(1);

        RecordType recordType = new RecordType();
        recordType.setId("1");
        recordType.setDate(dtf
                .newXMLGregorianCalendar(new GregorianCalendar()));
        recordType.setDescription("Record number: 1");

        try {
            trigger.sendBody(
                    SimpleFileIngestorRouteBuilder.HANDLE_RECORD_ROUTE_ENDPOINT_URI,
                    marshallToXml(recordType));
        } catch (CamelExecutionException e) {
            // expected
        }
        
        output.assertIsSatisfied();
    }
    
    @Test
    @DirtiesContext
    public void testNonRecoverableExternalServiceException() throws Exception {
        configureProcessRecordFailure(1, false);
        
        DatatypeFactory dtf = DatatypeFactory.newInstance();

        output.setExpectedMessageCount(0);

        RecordType recordType = new RecordType();
        recordType.setId("1");
        recordType.setDate(dtf
                .newXMLGregorianCalendar(new GregorianCalendar()));
        recordType.setDescription("Record number: 1");

        try {
            trigger.sendBody(
                    SimpleFileIngestorRouteBuilder.HANDLE_RECORD_ROUTE_ENDPOINT_URI,
                    marshallToXml(recordType));
        } catch (CamelExecutionException e) {
            // expected
        }
        
        output.assertIsSatisfied(1000);
    }

    @SuppressWarnings("deprecation")
    protected void configureJdbcFailure(final int failureCount)
            throws Exception {
        RouteDefinition routeDef = context
                .getRouteDefinition(SimpleFileIngestorRouteBuilder.PERSIST_RECORD_ROUTE_ID);

        routeDef.adviceWith(context, new RouteBuilder() {

            private AtomicInteger count = new AtomicInteger(0);

            @Override
            public void configure() throws Exception {
                interceptSendToEndpoint(
                        builder.getAlternatePersistEndpointUri()).process(
                        new Processor() {

                            @Override
                            public void process(Exchange exchange)
                                    throws Exception {
                                Record record = exchange.getIn().getBody(Record.class);

                                if ("1".equals(record.getId())
                                        && count.getAndIncrement() < failureCount) {
                                    throw new SQLException("Simulated JDBC Error!");
                                }
                            }
                        });
            }
        });
    }

    @SuppressWarnings("deprecation")
    protected void configureProcessRecordFailure(final int failureCount, 
            final boolean recoverableFailure) throws Exception {
        RouteDefinition routeDef = context
                .getRouteDefinition(SimpleFileIngestorRouteBuilder.PROCESS_RECORD_ROUTE_ID);

        routeDef.adviceWith(context, new RouteBuilder() {

            private AtomicInteger count = new AtomicInteger(0);

            @Override
            public void configure() throws Exception {
                interceptSendToEndpoint(
                        "bean:recordProcessor?method=processRecord").process(
                        new Processor() {

                            @Override
                            public void process(Exchange exchange) throws Exception {
                                Record record = exchange.getIn().getBody(Record.class);

                                if ("1".equals(record.getId())
                                        && count.getAndIncrement() < failureCount) {
                                    if (recoverableFailure) {
                                        throw new RecoverableExternalServiceException(
                                                "Simulated Processor Error!");
                                    } else {
                                        throw new NonRecoverableExternalServiceException(
                                                "Simulated Processor Error!");
                                    }
                                }
                            }
                        });
            }
        });
    }
	
	protected void createAndMoveFile(AggregateRecordType agt) throws Exception {
        File testFile = new File("./target/test.xml");
        
        if (testFile.exists()) {
            testFile.delete();
        }
        
        ObjectFactory objFact = new ObjectFactory();
        JAXBContext context = JAXBContext.newInstance(ObjectFactory.class);
        Marshaller m = context.createMarshaller();
        m.marshal(objFact.createAggregateRecord(agt), testFile);
        // not really atomic, but it works for tests
        FileUtils.moveFile(testFile, new File(pollingFolder, "test.xml"));
    }
	
	protected void validateFileMove(boolean expectFailure) throws Exception {
        
        File movedFile = new File((expectFailure ? failedFolder : doneFolder), "test.xml");
        
        for (int i = 10; i > 0; i--) {
            if (movedFile.exists()) {
                break;
            }
            Thread.sleep(1000);
        }

        assertTrue(movedFile.exists());
    }
	
	protected InputStream marshallToXml(RecordType recordType) throws Exception {
	    Marshaller marshaller = jaxbContext.createMarshaller();
	    ObjectFactory objFact = new ObjectFactory();
	    
	    ByteArrayOutputStream baos = new ByteArrayOutputStream();
	    
	    marshaller.marshal(objFact.createRecord(recordType), baos);
	    
	    return new ByteArrayInputStream(baos.toByteArray());
	}
}
