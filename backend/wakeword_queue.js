// Wakeword event queue with observable FIFO semantics, bounded capacity, and metric tracking

const MAX_WAKEWORD_QUEUE_SIZE = 100;
let queue = [];
let totalEnqueued = 0;
let totalDequeued = 0;
let droppedCount = 0;

function enqueueEvent(eventData, options = {}) {
  if (!eventData || typeof eventData !== 'object') {
    throw new Error('Invalid wakeword event: must be a non-null object');
  }

  const eventId = `wk_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
  const item = {
    eventId,
    event: eventData,
    receivedAt: Date.now(),
    status: options.awaitingToken ? 'AWAITING_DEVICE_TOKEN' : 'QUEUED_IN_MEMORY',
    durable: false
  };

  if (queue.length >= MAX_WAKEWORD_QUEUE_SIZE) {
    queue.shift();
    droppedCount++;
  }

  queue.push(item);
  totalEnqueued++;
  return item;
}

function dequeueEvents(limit = 10) {
  const count = Math.min(Math.max(1, parseInt(limit, 10) || 1), queue.length);
  const items = queue.splice(0, count);
  totalDequeued += items.length;
  return items;
}

function acknowledgeEvents(eventIds = []) {
  if (!Array.isArray(eventIds)) return 0;
  const idsToAck = new Set(eventIds);
  const initialLength = queue.length;
  queue = queue.filter(item => !idsToAck.has(item.eventId));
  const removed = initialLength - queue.length;
  totalDequeued += removed;
  return removed;
}

function getQueueStatus(options = {}) {
  const limit = options.limit ? Math.min(parseInt(options.limit, 10), 100) : 20;
  return {
    queueDepth: queue.length,
    maxCapacity: MAX_WAKEWORD_QUEUE_SIZE,
    totalEnqueued,
    totalDequeued,
    droppedCount,
    events: options.includeEvents ? queue.slice(-limit) : undefined,
    oldestTimestamp: queue.length > 0 ? queue[0].receivedAt : null,
    newestTimestamp: queue.length > 0 ? queue[queue.length - 1].receivedAt : null
  };
}

function peekEvents(limit = 20) {
  const count = Math.min(Math.max(1, parseInt(limit, 10) || 1), queue.length);
  return queue.slice(0, count);
}

function clearQueueForTesting() {
  queue = [];
  totalEnqueued = 0;
  totalDequeued = 0;
  droppedCount = 0;
}

module.exports = {
  MAX_WAKEWORD_QUEUE_SIZE,
  enqueueEvent,
  dequeueEvents,
  acknowledgeEvents,
  getQueueStatus,
  peekEvents,
  clearQueueForTesting
};
