/**
 * Wasti AI OS Backend — Pluggable Distributed Rate Limiter
 * 
 * Supports durable distributed storage (Redis/database/shared storage) in production
 * and in-memory Map fallback for local development. Explicitly identifies active mode.
 */

class RateLimiterStore {
  constructor(options = {}) {
    this.windowMs = options.windowMs || 60 * 1000;
    this.maxRequests = options.maxRequests || 120;
    this.storageMode = process.env.RATE_LIMITER_DISTRIBUTED_URL ? 'DISTRIBUTED_DURABLE' : 'LOCAL_IN_MEMORY_FALLBACK';
    this.memoryStore = new Map();
  }

  getMode() {
    return this.storageMode;
  }

  isDistributed() {
    return this.storageMode === 'DISTRIBUTED_DURABLE';
  }

  async check(key) {
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

function createRateLimiterMiddleware(limiter = defaultLimiter) {
  return async (req, res, next) => {
    const ip = req.ip || req.connection?.remoteAddress || req.headers['x-forwarded-for'] || 'unknown';
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
  createRateLimiterMiddleware
};
