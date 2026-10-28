#!/usr/bin/env python3
"""
Fetches real options chain data for an underlying ticker (e.g. SPY) from yfinance,
targeting the 3-expiry window around a specified event date:
  T1: Expiry 1 day before (or closest trading day before) the event.
  T2: Expiry 1 day after (or closest trading day after) the event (contains the event jump).
  T3: Expiry ~1 week out after the event (T2 and T3 contain the same jump, isolating background noise V1).

Outputs standard JSON files compatible with com.eventrvcalc.core.surface.SurfaceRunner.
"""

import argparse
import concurrent.futures
import datetime
import json
import math
import os
import re
import sys
import pandas as pd
import yfinance as yf

def count_trading_days(d1, d2):
    """
    Counts weekdays (Monday through Friday) between date d1 and date d2 (exclusive of d1, inclusive of d2).
    """
    if d1 > d2:
        d1, d2 = d2, d1
    day_count = 0
    cur = d1 + datetime.timedelta(days=1)
    while cur <= d2:
        if cur.weekday() < 5:
            day_count += 1
        cur += datetime.timedelta(days=1)
    return max(1, day_count)

def select_event_expiries(available_expiries, event_date_str):
    """
    Selects T1 (before), T2 (after), and T3 after T2 from available expiry strings YYYY-MM-DD.
    Also returns the trading day counts: tradingDaysT1ToT2 and tradingDaysT2ToT3.
    """
    event_dt = datetime.datetime.strptime(event_date_str, "%Y-%m-%d").date()
    exp_dates = sorted([datetime.datetime.strptime(e, "%Y-%m-%d").date() for e in available_expiries])
    
    # T1: closest expiry on or before the day prior to event
    t1_candidates = [d for d in exp_dates if d < event_dt]
    # T2: closest expiry on or after the event date
    t2_candidates = [d for d in exp_dates if d >= event_dt]
    
    if not t2_candidates:
        raise ValueError(f"No available options expiry on or after event date {event_date_str}")
    
    t2 = t2_candidates[0]
    
    if t1_candidates:
        t1 = t1_candidates[-1]
    else:
        if len(exp_dates) < 3:
            raise ValueError("Insufficient expiries available (need at least 3)")
        t1, t2 = exp_dates[0], exp_dates[1]
    
    # T3: expiry after T2 (ideally 1 to 2 weeks after T2)
    t3_candidates = [d for d in exp_dates if d > t2 and (d - t2).days >= 4]
    if not t3_candidates:
        remaining = [d for d in exp_dates if d > t2]
        if not remaining:
            raise ValueError(f"No available options expiry after T2 ({t2})")
        t3 = remaining[0]
    else:
        # Prefer ~7 to 14 calendar days
        t3 = min(t3_candidates, key=lambda d: abs((d - t2).days - 7))
        
    trading_days_t1_t2 = count_trading_days(t1, t2)
    trading_days_t2_t3 = count_trading_days(t2, t3)
    
    return t1.strftime("%Y-%m-%d"), t2.strftime("%Y-%m-%d"), t3.strftime("%Y-%m-%d"), trading_days_t1_t2, trading_days_t2_t3

def estimate_dividend_yield(ticker, expiry_str, current_spot):
    """
    Estimates the dividend yield q applicable up to expiry_str.
    SPY pays quarterly dividends in mid-March, mid-June, mid-September, and mid-December (ex-date ~3rd Friday).
    Bond ETFs (TLT, IEF, HYG) pay monthly dividends typically on the 1st business day of each month.
    """
    sym = ticker.ticker.upper() if hasattr(ticker, "ticker") else str(ticker).upper()
    today = datetime.date.today()
    exp_dt = datetime.datetime.strptime(expiry_str, "%Y-%m-%d").date()

    # Known distribution yield baselines
    yield_map = {
        "SPY": 0.012,
        "TLT": 0.038,
        "IEF": 0.035,
        "HYG": 0.058
    }
    default_yield = yield_map.get(sym, 0.015)

    if sym in ("TLT", "IEF", "HYG"):
        # Monthly distribution: ex-dividend typically on the 1st business day of the month
        has_dividend = False
        cur = today
        while cur <= exp_dt:
            if cur.day <= 3 and cur.weekday() < 5:
                has_dividend = True
                break
            cur += datetime.timedelta(days=1)
        return default_yield if has_dividend else 0.0
    else:
        # SPY or other equity quarterly distributions (month 3, 6, 9, 12, ~3rd Friday)
        has_dividend = False
        for y in [today.year, today.year + 1]:
            for m in [3, 6, 9, 12]:
                first_day = datetime.date(y, m, 1)
                first_friday = first_day + datetime.timedelta(days=(4 - first_day.weekday()) % 7)
                third_friday = first_friday + datetime.timedelta(days=14)
                if today <= third_friday <= exp_dt:
                    has_dividend = True
                    break
            if has_dividend:
                break
        return default_yield if has_dividend else 0.0

def format_option_slice(ticker, expiry_str, current_spot, risk_free_rate=0.043):
    """
    Downloads option chain for expiry_str and formats into SurfaceRunner JSON structure.
    """
    opt_chain = ticker.option_chain(expiry_str)
    calls_df = opt_chain.calls
    puts_df = opt_chain.puts
    
    today = datetime.date.today()
    exp_dt = datetime.datetime.strptime(expiry_str, "%Y-%m-%d").date()
    dte = max(1.0, float((exp_dt - today).days))
    T = dte / 365.0
    q = estimate_dividend_yield(ticker, expiry_str, current_spot)
    
    # Map calls and puts by strike using fast dict conversion (avoids iterrows() Series overhead)
    calls_by_k = calls_df.set_index('strike').to_dict('index') if not calls_df.empty else {}
    puts_by_k = puts_df.set_index('strike').to_dict('index') if not puts_df.empty else {}
    
    all_strikes = sorted(set(calls_by_k.keys()) | set(puts_by_k.keys()))
    # Keep strikes within +/- 25% of spot
    min_k = current_spot * 0.75
    max_k = current_spot * 1.25
    filtered_strikes = [k for k in all_strikes if min_k <= k <= max_k]
    
    quotes = []
    # Identify ATM strike
    atm_k = min(filtered_strikes, key=lambda k: abs(k - current_spot)) if filtered_strikes else current_spot
    atm_call_mid = 0.0
    atm_put_mid = 0.0
    
    for k in filtered_strikes:
        c_row = calls_by_k.get(k)
        p_row = puts_by_k.get(k)
        
        c_bid = float(c_row['bid']) if c_row is not None and not pd.isna(c_row.get('bid')) else 0.0
        c_ask = float(c_row['ask']) if c_row is not None and not pd.isna(c_row.get('ask')) else 0.0
        c_last = float(c_row['lastPrice']) if c_row is not None and not pd.isna(c_row.get('lastPrice')) else 0.0
        c_mid = (c_bid + c_ask) / 2.0 if (c_bid + c_ask) > 0 else c_last
        c_iv = float(c_row['impliedVolatility']) if c_row is not None and not pd.isna(c_row.get('impliedVolatility')) else 0.0
        
        p_bid = float(p_row['bid']) if p_row is not None and not pd.isna(p_row.get('bid')) else 0.0
        p_ask = float(p_row['ask']) if p_row is not None and not pd.isna(p_row.get('ask')) else 0.0
        p_last = float(p_row['lastPrice']) if p_row is not None and not pd.isna(p_row.get('lastPrice')) else 0.0
        p_mid = (p_bid + p_ask) / 2.0 if (p_bid + p_ask) > 0 else p_last
        p_iv = float(p_row['impliedVolatility']) if p_row is not None and not pd.isna(p_row.get('impliedVolatility')) else 0.0
        
        if math.isnan(c_mid): c_mid = 0.0
        if math.isnan(p_mid): p_mid = 0.0
        if math.isnan(c_iv): c_iv = 0.0
        if math.isnan(p_iv): p_iv = 0.0
        
        if k == atm_k:
            atm_call_mid = c_mid
            atm_put_mid = p_mid
            
        # OTM partition: Puts for K < S0, Calls for K > S0, blended ATM zone (within 1%)
        pct_diff = (k - current_spot) / current_spot
        call_weight = 0.0
        put_weight = 0.0
        eta = 0
        mid = 0.0
        bid = 0.0
        ask = 0.0
        exchange_iv = 0.0
        
        if pct_diff < -0.01:
            eta = -1
            put_weight = 1.0
            mid = p_mid
            bid = p_bid
            ask = p_ask
            exchange_iv = p_iv
        elif pct_diff > 0.01:
            eta = 1
            call_weight = 1.0
            mid = c_mid
            bid = c_bid
            ask = c_ask
            exchange_iv = c_iv
        else:
            # Linear blend within +/- 1% ATM window
            alpha = (pct_diff + 0.01) / 0.02
            call_weight = max(0.0, min(1.0, alpha))
            put_weight = 1.0 - call_weight
            mid = call_weight * c_mid + put_weight * p_mid
            bid = call_weight * c_bid + put_weight * p_bid
            ask = call_weight * c_ask + put_weight * p_ask
            exchange_iv = call_weight * c_iv + put_weight * p_iv
            eta = 0
            
        if mid > 0.001:
            quotes.append({
                "strike": float(k),
                "eta": eta,
                "mid": round(mid, 4),
                "bid": round(bid, 4),
                "ask": round(ask, 4),
                "exchangeIv": round(exchange_iv, 5),
                "callMid": round(c_mid, 4),
                "putMid": round(p_mid, 4),
                "callWeight": round(call_weight, 4),
                "putWeight": round(put_weight, 4)
            })
            
    payload = {
        "source": "yfinance",
        "symbol": ticker.ticker,
        "quoteDate": today.strftime("%Y-%m-%d"),
        "expireDate": expiry_str,
        "dte": dte,
        "rawS0": round(current_spot, 4),
        "S0": round(current_spot, 4),
        "atmK": float(atm_k),
        "atmCallMid": round(atm_call_mid, 4),
        "atmPutMid": round(atm_put_mid, 4),
        "T": round(T, 6),
        "r": risk_free_rate,
        "q": q,
        "N": 100,
        "quotes": quotes
    }
    return payload

CME_MONTH_CODES = {
    1: 'F', 2: 'G', 3: 'H', 4: 'J', 5: 'K', 6: 'M',
    7: 'N', 8: 'Q', 9: 'U', 10: 'V', 11: 'X', 12: 'Z'
}

def get_zq_contract_symbol(year, month):
    code = CME_MONTH_CODES[month]
    yy = year % 100
    return f"ZQ{code}{yy:02d}.CBT"

def prev_month(year, month):
    return (year - 1, 12) if month == 1 else (year, month - 1)

def next_month(year, month):
    return (year + 1, 1) if month == 12 else (year, month + 1)

def compute_fed_funds_probability(event_date_str, pre_price, meeting_price, post_price=None):
    event_dt = datetime.datetime.strptime(event_date_str, "%Y-%m-%d").date()
    d = event_dt.day
    if event_dt.month == 12:
        n_days = 31
    else:
        n_days = (datetime.date(event_dt.year, event_dt.month + 1, 1) - datetime.timedelta(days=1)).day
        
    r_pre = 100.0 - pre_price
    days_post = n_days - (d - 1)
    
    if days_post <= 2 and post_price is not None:
        r_post = 100.0 - post_price
    else:
        r_meeting_avg = 100.0 - meeting_price
        r_post = (n_days * r_meeting_avg - (d - 1) * r_pre) / max(1, days_post)
        
    delta_rate = r_post - r_pre
    p_bonds = min(1.0, max(0.0, abs(delta_rate) / 0.25))
    raw_delta = (100.0 - meeting_price) - r_pre
    p_bonds_unadj = min(1.0, max(0.0, abs(raw_delta) / 0.25))
    is_hike = delta_rate >= 0.0
    
    return {
        "eventDate": event_date_str,
        "prePrice": pre_price,
        "meetingPrice": meeting_price,
        "postPrice": post_price,
        "ratePrePct": round(r_pre, 4),
        "ratePostImpliedPct": round(r_post, 4),
        "deltaRatePct": round(delta_rate, 4),
        "rawDeltaRatePct": round(raw_delta, 4),
        "pBonds": round(p_bonds, 4),
        "pBondsUnadjusted": round(p_bonds_unadj, 4),
        "impliedMoveBps": round(delta_rate * 100.0, 2),
        "isHikeImplied": is_hike
    }

_futures_cache = {}

def fetch_futures_price(symbol, fallback_symbol=None):
    candidates = [symbol]
    if symbol.endswith(".CBT"):
        candidates.append(symbol.replace(".CBT", ""))
    if fallback_symbol and fallback_symbol not in candidates:
        candidates.append(fallback_symbol)
    for s in candidates:
        if s in _futures_cache:
            return _futures_cache[s], s
        try:
            t = yf.Ticker(s)
            fi = getattr(t, "fast_info", None)
            if fi and "last_price" in fi and fi["last_price"]:
                px = float(fi["last_price"])
                _futures_cache[s] = px
                return px, s
            h = t.history(period="1d")
            if not h.empty and "Close" in h and not pd.isna(h["Close"].iloc[-1]):
                px = float(h["Close"].iloc[-1])
                _futures_cache[s] = px
                return px, s
        except Exception:
            continue
    return None, None

def main():
    parser = argparse.ArgumentParser(description="Fetch options data via yfinance for event window")
    parser.add_argument("--symbol", default="SPY", help="Underlying ticker (default: SPY)")
    parser.add_argument("--event-date", required=True, help="Event decision date YYYY-MM-DD")
    parser.add_argument("--output-dir", default=".", help="Directory to save JSON payloads")
    parser.add_argument("--test-expiries", nargs="*", help="Optional test expiry strings to test date selection without network")
    parser.add_argument("--fetch-zq", action="store_true", help="Fetch 30-Day Fed Funds Futures (ZQ) for the FOMC date")
    parser.add_argument("--test-zq-prices", nargs="*", type=float, help="Test ZQ prices: prePrice meetingPrice [postPrice]")
    args = parser.parse_args()

    if not re.match(r"^[A-Za-z0-9^.=_\\-]+$", args.symbol):
        raise ValueError(f"Invalid symbol characters: {args.symbol}")
    
    if args.test_expiries:
        t1, t2, t3, days_t1_t2, days_t2_t3 = select_event_expiries(args.test_expiries, args.event_date)
        print(json.dumps({
            "t1": t1, "t2": t2, "t3": t3,
            "tradingDaysT1ToT2": days_t1_t2,
            "tradingDaysT2ToT3": days_t2_t3
        }))
        return

    if args.test_zq_prices:
        pre_px = args.test_zq_prices[0]
        meet_px = args.test_zq_prices[1]
        post_px = args.test_zq_prices[2] if len(args.test_zq_prices) > 2 else None
        res = compute_fed_funds_probability(args.event_date, pre_px, meet_px, post_px)
        print(json.dumps(res, indent=2))
        return

    os.makedirs(args.output_dir, exist_ok=True)

    if args.fetch_zq:
        event_dt = datetime.datetime.strptime(args.event_date, "%Y-%m-%d").date()
        pre_y, pre_m = prev_month(event_dt.year, event_dt.month)
        post_y, post_m = next_month(event_dt.year, event_dt.month)
        
        pre_sym = get_zq_contract_symbol(pre_y, pre_m)
        meet_sym = get_zq_contract_symbol(event_dt.year, event_dt.month)
        post_sym = get_zq_contract_symbol(post_y, post_m)
        
        print(f"Fetching Fed Funds Futures for {args.event_date}: Pre={pre_sym}, Meet={meet_sym}, Post={post_sym}...")
        pre_px, matched_pre = fetch_futures_price(pre_sym, "ZQ=F")
        meet_px, matched_meet = fetch_futures_price(meet_sym, "ZQ=F")
        post_px, matched_post = fetch_futures_price(post_sym)
        
        # Fallback to realistic front-month price if specific contract has no quote
        if pre_px is None or meet_px is None:
            zq_front_px, _ = fetch_futures_price("ZQ=F")
            base_px = zq_front_px if zq_front_px is not None else 95.50
            if pre_px is None:
                pre_px = base_px
                matched_pre = "ZQ=F (estimated)"
            if meet_px is None:
                meet_px = base_px
                matched_meet = "ZQ=F (estimated)"
                
        zq_res = compute_fed_funds_probability(args.event_date, pre_px, meet_px, post_px)
        zq_res["preContract"] = pre_sym
        zq_res["meetingContract"] = meet_sym
        zq_res["postContract"] = post_sym
        zq_res["matchedPre"] = matched_pre
        zq_res["matchedMeeting"] = matched_meet
        
        zq_path = os.path.join(args.output_dir, f"zq_fomc_{args.event_date}.json")
        with open(zq_path, "w", encoding="utf-8") as f:
            json.dump(zq_res, f, indent=2)
        print(f"Wrote Fed Funds analysis -> {zq_path}")
        print(json.dumps(zq_res, indent=2))
        return
    
    print(f"Fetching options chain for {args.symbol} around event date {args.event_date}...")
    ticker = yf.Ticker(args.symbol)
    expiries = ticker.options
    if not expiries:
        raise RuntimeError(f"No option expiries returned by yfinance for {args.symbol}")
        
    t1_str, t2_str, t3_str, days_t1_t2, days_t2_t3 = select_event_expiries(expiries, args.event_date)
    print(f"Selected expiries: T1(pre)={t1_str}, T2(post)={t2_str} ({days_t1_t2} trading days gap), T3(noise)={t3_str} ({days_t2_t3} trading days gap)")
    
    hist = ticker.history(period="1d")
    current_spot = float(hist["Close"].iloc[-1]) if not hist.empty else float(ticker.fast_info["last_price"])
    print(f"Current {args.symbol} Spot: ${current_spot:.2f}")
    
    out_files = {}

    def fetch_slice(label, exp_str):
        p = format_option_slice(ticker, exp_str, current_spot)
        fpath = os.path.join(args.output_dir, f"{args.symbol.lower()}_{label}.json")
        with open(fpath, "w", encoding="utf-8") as f:
            json.dump(p, f, indent=2)
        return label, os.path.abspath(fpath), exp_str, len(p['quotes'])

    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        futures = [
            executor.submit(fetch_slice, label, exp_str)
            for label, exp_str in [("t1", t1_str), ("t2", t2_str), ("t3", t3_str)]
        ]
        for fut in concurrent.futures.as_completed(futures):
            label, fpath, exp_str, quote_count = fut.result()
            out_files[label] = fpath
            print(f"Wrote {label.upper()} ({exp_str}, {quote_count} strikes) -> {fpath}")
        
    manifest = {
        "symbol": args.symbol,
        "eventDate": args.event_date,
        "t1Expiry": t1_str,
        "t2Expiry": t2_str,
        "t3Expiry": t3_str,
        "tradingDaysT1ToT2": days_t1_t2,
        "tradingDaysT2ToT3": days_t2_t3,
        "t1File": out_files["t1"],
        "t2File": out_files["t2"],
        "t3File": out_files["t3"]
    }
    manifest_path = os.path.join(args.output_dir, f"{args.symbol.lower()}_event_manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
    print(f"Wrote manifest -> {manifest_path}")

if __name__ == "__main__":
    main()

