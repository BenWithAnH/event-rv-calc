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
import datetime
import json
import os
import sys

def select_event_expiries(available_expiries, event_date_str):
    """
    Selects T1 (before), T2 (after), and T3 (~1 week after T2) from available expiry strings YYYY-MM-DD.
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
        # If event is today or earlier than all expiries, pick the earliest two expiries
        if len(exp_dates) < 3:
            raise ValueError("Insufficient expiries available (need at least 3)")
        t1, t2 = exp_dates[0], exp_dates[1]
    
    # T3: ~1 week (5 to 10 trading days, 5 to 14 calendar days) after T2
    t3_candidates = [d for d in exp_dates if d > t2 and (d - t2).days >= 4]
    if not t3_candidates:
        # fallback to next available expiry after T2
        remaining = [d for d in exp_dates if d > t2]
        if not remaining:
            raise ValueError(f"No available options expiry after T2 ({t2})")
        t3 = remaining[0]
    else:
        # prefer ~7 calendar days
        t3 = min(t3_candidates, key=lambda d: abs((d - t2).days - 7))
        
    return t1.strftime("%Y-%m-%d"), t2.strftime("%Y-%m-%d"), t3.strftime("%Y-%m-%d")

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
    
    # Map calls and puts by strike
    calls_by_k = {row['strike']: row for _, row in calls_df.iterrows()}
    puts_by_k = {row['strike']: row for _, row in puts_df.iterrows()}
    
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
        
        c_bid = float(c_row['bid']) if c_row is not None and not pd.isna(c_row['bid']) else 0.0
        c_ask = float(c_row['ask']) if c_row is not None and not pd.isna(c_row['ask']) else 0.0
        c_mid = (c_bid + c_ask) / 2.0 if (c_bid + c_ask) > 0 else (float(c_row['lastPrice']) if c_row is not None else 0.0)
        c_iv = float(c_row['impliedVolatility']) if c_row is not None and not pd.isna(c_row['impliedVolatility']) else 0.0
        
        p_bid = float(p_row['bid']) if p_row is not None and not pd.isna(p_row['bid']) else 0.0
        p_ask = float(p_row['ask']) if p_row is not None and not pd.isna(p_row['ask']) else 0.0
        p_mid = (p_bid + p_ask) / 2.0 if (p_bid + p_ask) > 0 else (float(p_row['lastPrice']) if p_row is not None else 0.0)
        p_iv = float(p_row['impliedVolatility']) if p_row is not None and not pd.isna(p_row['impliedVolatility']) else 0.0
        
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
        "q": 0.0,
        "N": 100,
        "quotes": quotes
    }
    return payload

def main():
    parser = argparse.ArgumentParser(description="Fetch options data via yfinance for event window")
    parser.add_argument("--symbol", default="SPY", help="Underlying ticker (default: SPY)")
    parser.add_argument("--event-date", required=True, help="Event decision date YYYY-MM-DD")
    parser.add_argument("--output-dir", default=".", help="Directory to save JSON payloads")
    parser.add_argument("--test-expiries", nargs="*", help="Optional test expiry strings to test date selection without network")
    args = parser.parse_args()
    
    if args.test_expiries:
        t1, t2, t3 = select_event_expiries(args.test_expiries, args.event_date)
        print(json.dumps({"t1": t1, "t2": t2, "t3": t3}))
        return
        
    import yfinance as yf
    import pandas as pd
    global pd
    
    print(f"Fetching options chain for {args.symbol} around event date {args.event_date}...")
    ticker = yf.Ticker(args.symbol)
    expiries = ticker.options
    if not expiries:
        raise RuntimeError(f"No option expiries returned by yfinance for {args.symbol}")
        
    t1_str, t2_str, t3_str = select_event_expiries(expiries, args.event_date)
    print(f"Selected expiries: T1(pre)={t1_str}, T2(post)={t2_str}, T3(1-wk)={t3_str}")
    
    hist = ticker.history(period="1d")
    current_spot = float(hist["Close"].iloc[-1]) if not hist.empty else float(ticker.fast_info["last_price"])
    print(f"Current {args.symbol} Spot: ${current_spot:.2f}")
    
    os.makedirs(args.output_dir, exist_ok=True)
    out_files = {}
    for label, exp_str in [("t1", t1_str), ("t2", t2_str), ("t3", t3_str)]:
        payload = format_option_slice(ticker, exp_str, current_spot)
        file_path = os.path.join(args.output_dir, f"{args.symbol.lower()}_{label}.json")
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2)
        out_files[label] = os.path.abspath(file_path)
        print(f"Wrote {label.upper()} ({exp_str}, {len(payload['quotes'])} strikes) -> {file_path}")
        
    manifest = {
        "symbol": args.symbol,
        "eventDate": args.event_date,
        "t1Expiry": t1_str,
        "t2Expiry": t2_str,
        "t3Expiry": t3_str,
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

