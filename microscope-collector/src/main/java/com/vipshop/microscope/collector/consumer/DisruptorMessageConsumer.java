package com.vipshop.microscope.collector.consumer;

import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.vipshop.micorscope.framework.util.ThreadPoolUtil;
import com.vipshop.microscope.collector.disruptor.AnalyzeEventHandler;
import com.vipshop.microscope.collector.disruptor.SpanEvent;
import com.vipshop.microscope.collector.disruptor.StorageEventHandler;
import com.vipshop.microscope.thrift.gen.Span;

/**
 * A version use {@code Disruptor} to consume spans.
 * 
 * @author Xu Fei
 * @version 1.0
 */
public class DisruptorMessageConsumer implements MessageConsumer {
	
	private static final Logger logger = LoggerFactory.getLogger(DisruptorMessageConsumer.class);
	
	private static final int BUFFER_SIZE = 1024 * 8 * 8 * 8;
	
	private volatile boolean start = false;
	
	private final RingBuffer<SpanEvent> ringBuffer;
	
	private final SequenceBarrier sequenceBarrier;
	
	private final AnalyzeEventHandler analyzeHandler;
	private final BatchEventProcessor<SpanEvent> analyzeEventProcessor;

	private final StorageEventHandler storageHandler;
	private final BatchEventProcessor<SpanEvent> storageEventProcessor;
	
	public DisruptorMessageConsumer() {
		ringBuffer = RingBuffer.createSingleProducer(SpanEvent.EVENT_FACTORY, BUFFER_SIZE, new SleepingWaitStrategy());
		
		sequenceBarrier = ringBuffer.newBarrier();
		
		analyzeHandler = new AnalyzeEventHandler();
		analyzeEventProcessor = new BatchEventProcessor<SpanEvent>(ringBuffer, sequenceBarrier, analyzeHandler);

		storageHandler = new StorageEventHandler();
		storageEventProcessor = new BatchEventProcessor<SpanEvent>(ringBuffer, sequenceBarrier, storageHandler);

		ringBuffer.addGatingSequences(analyzeEventProcessor.getSequence());
		ringBuffer.addGatingSequences(storageEventProcessor.getSequence());
	}
	
	public void start() {
		logger.info("use message consumer base on disruptor ");

		logger.info("start storage thread pool with size 1");
		ExecutorService executor = ThreadPoolUtil.newFixedThreadPool(1, "store-span-pool");
		executor.execute(this.storageEventProcessor);
		
		logger.info("start analyze thread pool with size 1");
		ExecutorService analyzeExecutor = ThreadPoolUtil.newFixedThreadPool(1, "analyze-span-pool");
		analyzeExecutor.execute(this.analyzeEventProcessor);
		
		start = true;
	}
	
	public void publish(Span span) {
		if (start && span != null) {
			long sequence = this.ringBuffer.next();
			this.ringBuffer.get(sequence).setSpan(span);
			this.ringBuffer.publish(sequence);
		}
	}
	
	public void shutdown() {
		storageEventProcessor.halt();
		analyzeEventProcessor.halt();
	}

}