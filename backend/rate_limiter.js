/**
 * Wasti AI OS Backend — Pluggable Distributed Rate Limiter
 * 
 * Supports durable distributed storage (Redis/database/shared storage) in production
 * and in-memory Map fallback for local development. Explicitly identifies active mode.
 */

function getClientKey(req, trustProxy = false) {
  if (trustProxy && req.ip) {
    return req.ip;
  }
  const socketAddress = req.socket?.remoteAddress || req.connection?.remoteAddress;
  if (socketAddress) {
    return socketAddress;
  }
  if (trustProxy && req.headers && req.headers['x-forwarded-for']) {
    const forwarded = String(req.headers['x-forwarded-for']).split(',')[0].trim();
    if (forwarded) return forwarded;
  }
  return req.ip || 'unknown';
}

class RateLimiterStore {
  constructor(options = {}) {
    this.windowMs = options.windowMs || 60 * 1000;
    this.maxRequests = options.maxRequests || 120;
    this.distributedClient = options.distributedClient || null;
    this.memoryStore = new Map();
    // Only claim DISTRIBUTED_DURABLE if a genuine distributed client exists and reports active connectivity
    const hasActiveDistributed = !!(
      this.distributedClient &&
      typeof this.distributedClient.isConnected === 'function' &&
      this.distributedClient.isConnected()
    );
    this.storageMode = hasActiveDistributed ? 'DISTRIBUTED_DURABLE' : 'LOCAL_IN_MEMORY_FALLBACK';
  }

  getMode() {
    return this.storageMode;
  }

  isDistributed() {
    return this.storageMode === 'DISTRIBUTED_DURABLE';
  }

  async check(key) {
    if (this.isDistributed() && this.distributedClient) {
      try {
        return await this.distributedClient.check(key, this.windowMs, this.maxRequests);
      } catch (err) {
        // Fall back safely to in-memory store if distributed check fails
        this.storageMode = 'LOCAL_IN_MEMORY_FALLBACK';
      }
    }

    const now = Date.now();
    let record = this.memoryStore.get(key);

    if (!record || now > record.resetAt) {
      record = { count: 1, resetAt: now + this.windowMs };
      this.memoryStore.set(key, record);
      return {
        allowed: true,
        current: 1,
        remaining: this.maxRequests - 1,
        resetAt: record.resetAt,
        retryAfterMs: 0,
        mode: this.storageMode
      };
    }

    record.count++;
    this.memoryStore.set(key, record);

    if (record.count > this.maxRequests) {
      return {
        allowed: false,
        current: record.count,
        remaining: 0,
        resetAt: record.resetAt,
        retryAfterMs: Math.max(0, record.resetAt - now),
        mode: this.storageMode
      };
    }

    return {
      allowed: true,
      current: record.count,
      remaining: this.maxRequests - record.count,
      resetAt: record.resetAt,
      retryAfterMs: 0,
      mode: this.storageMode
    };
  }

  reset(key) {
    if (key) {
      this.memoryStore.delete(key);
    } else {
      this.memoryStore.clear();
    }
  }
}

const defaultLimiter = new RateLimiterStore();

function createRateLimiterMiddleware(limiter = defaultLimiter, options = {}) {
  return async (req, res, next) => {
    const trustProxy = req.app?.get?.('trust proxy') ?? options.trustProxy ?? false;
    const ip = getClientKey(req, trustProxy);
    const result = await limiter.check(ip);

    res.setHeader('X-RateLimit-Limit', limiter.maxRequests);
    res.setHeader('X-RateLimit-Remaining', result.remaining);
    res.setHeader('X-RateLimit-Reset', Math.ceil(result.resetAt / 1000));
    res.setHeader('X-RateLimit-Mode', result.mode);

    if (!result.allowed) {
      return res.status(429).json({
        error: 'Too Many Requests',
        retryAfterMs: result.retryAfterMs,
        rateLimitMode: result.mode
      });
    }
    next();
  };
}

module.exports = {
  RateLimiterStore,
  defaultLimiter,
  getClientKey,
  createRateLimiterMiddleware
};
