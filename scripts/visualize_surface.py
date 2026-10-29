#!/usr/bin/env python3
"""
Visualization and exploration script for CRR and BS programs using real SPY test data
(spy_eod_2023.parquet).

Workflow:
  1. Ingests real market option quotes from spy_eod_2023.parquet.
  2. Extracts a liquid option slice (default: 2023-01-03, 45 DTE monthly expiry).
  3. Passes the market quotes to the Java SurfaceRunner, which calls:
       - CRR.findSigmaHat: De-Americanizes each strike to extract sigmaHat_K.
       - BS.fitVolCurve: Fits the quadratic volatility smile trendline sigma(K).
       - BS.generatePriceCurve: Computes smooth synthetic European call & put curves.
  4. Generates a 4-panel diagnostic visualization from the real market data.
"""

import os
import sys
import json
import shutil
import subprocess
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

# Anchor paths relative to the project root (parent directory of scripts/)
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)

def extract_spy_slice(parquet_path=None, quote_date="2023-01-03", target_dte=45.0):
    if parquet_path is None:
        parquet_path = os.path.join(PROJECT_ROOT, "spy_eod_2023.parquet")
    elif not os.path.isabs(parquet_path):
        parquet_path = os.path.join(PROJECT_ROOT, parquet_path)

    if not os.path.exists(parquet_path):
        raise FileNotFoundError(f"Parquet file not found: {parquet_path}")

    print(f"Reading SPY options from {parquet_path}...")
    df = pd.read_parquet(parquet_path)
    df.columns = [c.strip("[] ") for c in df.columns]

    # Filter by quote date
    df_date = df[df["QUOTE_DATE"] == quote_date]
    if df_date.empty:
        available_dates = df["QUOTE_DATE"].unique()[:5]
        raise ValueError(f"No quotes for date {quote_date}. Available: {available_dates}")

    # Find closest expiry to target_dte
    dtes = df_date["DTE"].unique()
    closest_dte = min(dtes, key=lambda d: abs(d - target_dte))
    slice_df = df_date[df_date["DTE"] == closest_dte].copy()
    slice_df = slice_df.sort_values("STRIKE")

    S0 = float(slice_df["UNDERLYING_LAST"].iloc[0])
    expire_date = str(slice_df["EXPIRE_DATE"].iloc[0])
    T = float(closest_dte) / 365.0

    # Macro parameters for early 2023:
    # 3-month US Treasury yield ~4.3% (0.043), set q = 0.0 per instructions
    r = 0.043
    q = 0.0
    N = 100

    # Find the strike closest to raw Spot (K = 381.0) and imply effective Spot from Put-Call Parity:
    # S_Implied_True = C_381 - (P_381 - EEP_381_Put) + 381 * exp(-r * T)
    raw_s0 = float(slice_df["UNDERLYING_LAST"].iloc[0])
    closest_idx = (slice_df["STRIKE"] - raw_s0).abs().idxmin()
    atm_row = slice_df.loc[closest_idx]
    atm_k = float(atm_row["STRIKE"])
    atm_c_mid = (float(atm_row["C_BID"]) + float(atm_row["C_ASK"])) / 2.0
    atm_p_mid = (float(atm_row["P_BID"]) + float(atm_row["P_ASK"])) / 2.0

    # 1. Initial Put-Call Parity:
    s_raw_parity = (atm_c_mid - atm_p_mid) + atm_k * np.exp(-r * T)

    # 2. Compute exact Put EEP at strike closest to spot using CRR tree:
    def calc_crr_put_eep(spot, strike, T_exp, rate, div, p_target, n_steps=100):
        from scipy.optimize import brentq
        dt = T_exp / n_steps
        disc = np.exp(-rate * dt)
        def tree_put(sigma, american):
            u_val = np.exp(sigma * np.sqrt(dt))
            d_val = 1.0 / u_val
            p_val = (np.exp((rate - div) * dt) - d_val) / (u_val - d_val)
            j_arr = np.arange(n_steps + 1)
            S_arr = spot * (u_val ** j_arr) * (d_val ** (n_steps - j_arr))
            V_arr = np.maximum(0.0, strike - S_arr)
            for i_idx in range(n_steps - 1, -1, -1):
                cont_val = disc * (p_val * V_arr[1:i_idx+2] + (1.0 - p_val) * V_arr[:i_idx+1])
                if american:
                    s_nodes = spot * (u_val ** np.arange(i_idx + 1)) * (d_val ** (i_idx - np.arange(i_idx + 1)))
                    V_arr = np.maximum(strike - s_nodes, cont_val)
                else:
                    V_arr = cont_val
            return V_arr[0]
        sig_hat = brentq(lambda s: tree_put(s, True) - p_target, 0.05, 1.0)
        p_euro = tree_put(sig_hat, False)
        return p_target - p_euro

    eep_381_put = calc_crr_put_eep(s_raw_parity, atm_k, T, r, q, atm_p_mid, N)

    # True implied spot applying Put-Call Parity to de-Americanized European prices:
    s_implied_true = atm_c_mid - (atm_p_mid - eep_381_put) + atm_k * np.exp(-r * T)

    print(f"Closest strike to spot: K={atm_k:.1f} (C_mid={atm_c_mid:.2f}, P_mid={atm_p_mid:.2f})")
    print(f"Raw underlying spot: ${raw_s0:.2f} -> Raw PCP spot: ${s_raw_parity:.4f}")
    print(f"Put EEP at K={atm_k:.1f}: ${eep_381_put:.4f} -> S_Implied_True: ${s_implied_true:.4f} (with q=0.0)")

    S0 = s_implied_true

    # Filter liquid OTM quotes within +/-15% of spot (active liquidity zone)
    min_strike = S0 * 0.85
    max_strike = S0 * 1.15
    slice_df = slice_df[(slice_df["STRIKE"] >= min_strike) & (slice_df["STRIKE"] <= max_strike)]

    # Blending window around S0: +/- $2 window around S0 (e.g. for K in [379, 383])
    # Weight Put IV and Call IV smoothly from 100%/0% to 0%/100%
    k_min_blend = 379.0
    k_max_blend = 383.0

    quotes = []
    for _, row in slice_df.iterrows():
        K = float(row["STRIKE"])
        c_bid = float(row["C_BID"])
        c_ask = float(row["C_ASK"])
        c_iv = float(row["C_IV"]) if pd.notna(row["C_IV"]) else 0.0
        c_mid = (c_bid + c_ask) / 2.0

        p_bid = float(row["P_BID"])
        p_ask = float(row["P_ASK"])
        p_iv = float(row["P_IV"]) if pd.notna(row["P_IV"]) else 0.0
        p_mid = (p_bid + p_ask) / 2.0

        if K < k_min_blend:
            # 100% Put
            if p_bid <= 0.02 or p_ask <= p_bid:
                continue
            spread_pct = (p_ask - p_bid) / p_mid
            if spread_pct > 0.50:
                continue
            quotes.append({
                "strike": K,
                "eta": -1,
                "mid": p_mid,
                "bid": p_bid,
                "ask": p_ask,
                "exchangeIv": p_iv,
                "callWeight": 0.0,
                "putWeight": 1.0
            })
        elif K > k_max_blend:
            # 100% Call
            if c_bid <= 0.02 or c_ask <= c_bid:
                continue
            spread_pct = (c_ask - c_bid) / c_mid
            if spread_pct > 0.50:
                continue
            quotes.append({
                "strike": K,
                "eta": 1,
                "mid": c_mid,
                "bid": c_bid,
                "ask": c_ask,
                "exchangeIv": c_iv,
                "callWeight": 1.0,
                "putWeight": 0.0
            })
        else:
            # Transition zone K in [379, 383]: blend smoothly from 100%/0% to 0%/100%
            w_call = (K - k_min_blend) / (k_max_blend - k_min_blend)
            w_put = 1.0 - w_call
            blended_mid = w_call * c_mid + w_put * p_mid
            blended_bid = w_call * c_bid + w_put * p_bid
            blended_ask = w_call * c_ask + w_put * p_ask
            blended_iv = w_call * c_iv + w_put * p_iv

            eta = -1 if w_call == 0.0 else (1 if w_put == 0.0 else 0)
            quotes.append({
                "strike": K,
                "eta": eta,
                "mid": blended_mid,
                "bid": blended_bid,
                "ask": blended_ask,
                "exchangeIv": blended_iv,
                "callMid": c_mid,
                "putMid": p_mid,
                "callWeight": w_call,
                "putWeight": w_put
            })

    print(f"Extracted {len(quotes)} liquid quotes for SPY with +/-$2 ATM blending (S_implied={S0:.2f}, Exp={expire_date}, DTE={closest_dte:.0f}).")
    return {
        "quoteDate": quote_date,
        "expireDate": expire_date,
        "dte": closest_dte,
        "rawS0": raw_s0,
        "S0": S0,
        "atmK": atm_k,
        "atmCallMid": atm_c_mid,
        "atmPutMid": atm_p_mid,
        "T": T,
        "r": r,
        "q": q,
        "N": N,
        "quotes": quotes
    }

def run_java_surface_pipeline(input_dict, input_file=None, output_file=None):
    if input_file is None:
        input_file = os.path.join(PROJECT_ROOT, "data", "spy_input.json")
    if output_file is None:
        output_file = os.path.join(PROJECT_ROOT, "target", "surface_data.json")

    if not os.path.isabs(input_file):
        input_file = os.path.join(PROJECT_ROOT, input_file)
    if not os.path.isabs(output_file):
        output_file = os.path.join(PROJECT_ROOT, output_file)

    with open(input_file, "w", encoding="utf-8") as f:
        json.dump(input_dict, f, indent=2)
    print(f"Saved input slice to {input_file}")

    print("Running Java CRR/BS SurfaceRunner...")
    cp_file = os.path.join(PROJECT_ROOT, "target", "classpath.txt")
    if os.path.exists(cp_file):
        with open(cp_file, "r", encoding="utf-8") as f:
            cp_str = f.read().strip() + os.pathsep + os.path.join(PROJECT_ROOT, "target", "classes")
        cmd = ["java", "-cp", cp_str, "com.eventrvcalc.core.surface.SurfaceRunner", input_file, output_file]
    else:
        cmd = [
            "mvn.cmd", "exec:java",
            "-Dexec.mainClass=com.eventrvcalc.core.surface.SurfaceRunner",
            f"-Dexec.args={input_file} {output_file}"
        ]
    subprocess.run(cmd, shell=True, check=True, cwd=PROJECT_ROOT)
    print(f"Java pipeline completed. Results written to {output_file}")

    with open(output_file, "r", encoding="utf-8") as f:
        return json.load(f)

def plot_surface(data, output_png="surface_exploration.png"):
    if not os.path.isabs(output_png):
        output_png = os.path.join(PROJECT_ROOT, output_png)
    S0 = data["S0"]
    T = data["T"]
    r = data["r"]
    q = data["q"]
    quote_date = data.get("quoteDate", "2023-01-03")
    expire_date = data.get("expireDate", "2023-02-17")
    dte = data.get("dte", T * 365.0)

    fitted_a = data["fittedA"]
    fitted_b = data["fittedB"]
    fitted_c = data["fittedC"]
    svi = data.get("svi")

    quotes = data["quotes"]
    euro_calls = data["syntheticEuroCalls"]
    euro_puts = data["syntheticEuroPuts"]
    convergence = data["convergence"]

    plt.style.use("seaborn-v0_8-darkgrid" if "seaborn-v0_8-darkgrid" in plt.style.available else "default")
    fig, axes = plt.subplots(2, 2, figsize=(14, 10), dpi=160)
    fig.suptitle(
        f"Real Market SPY Surface Exploration (CRR De-Americanization & 5-Parameter SVI)\n"
        f"Quote Date: {quote_date} | Expiry: {expire_date} (DTE={dte:.0f}) | Spot S0=${S0:.2f} | r={r*100:.2f}% | q={q*100:.2f}%",
        fontsize=13, fontweight="bold", y=0.98
    )

    # -------------------------------------------------------------
    # Panel 1: Implied Volatility Smile & Fitted SVI Curve w(k)
    # -------------------------------------------------------------
    ax1 = axes[0, 0]
    strikes = np.array([q_item["strike"] for q_item in quotes])
    vols = np.array([q_item["deAmSigmaHat"] * 100 for q_item in quotes])
    exch_vols = np.array([q_item["exchangeIv"] * 100 for q_item in quotes])
    etas = [q_item["eta"] for q_item in quotes]

    # Calculate dynamic blend window around Spot S0 (+/- 0.5% or at least $2.00)
    blend_half_width = max(2.0, S0 * 0.005)
    blend_low = S0 - blend_half_width
    blend_high = S0 + blend_half_width

    k_grid = np.linspace(strikes.min() - 5, strikes.max() + 5, 250)

    # Plot 5-parameter SVI fit: w(k) = a + b * (rho * (k - m) + sqrt((k - m)^2 + s^2))
    if svi:
        svi_a = svi["a"]
        svi_b = svi["b"]
        svi_rho = svi["rho"]
        svi_m = svi["m"]
        svi_s = svi["s"]
        vtx_k = svi.get("vertexStrike", S0 * np.exp(svi_m))

        k_log = np.log(k_grid / S0)
        diff = k_log - svi_m
        svi_w = svi_a + svi_b * (svi_rho * diff + np.sqrt(diff**2 + svi_s**2))
        svi_vols = np.sqrt(np.maximum(1e-8, svi_w) / T) * 100
        ax1.plot(k_grid, svi_vols, color="#1f77b4", lw=2.5, zorder=4,
                 label=rf"5-Param SVI: $m={svi_m:+.3f}$ ($K_{{vtx}}=\${vtx_k:.1f}$)")
        ax1.axvline(vtx_k, color="#ff7f0e", linestyle=":", lw=1.6, alpha=0.9,
                    label=rf"SVI Vertex $K_{{vtx}} = \${vtx_k:.1f}$")

    # Also show quadratic smile fit for reference
    fit_vols = (fitted_a * k_grid**2 + fitted_b * k_grid + fitted_c) * 100
    ax1.plot(k_grid, fit_vols, color="#aec7e8", linestyle="--", lw=1.5, alpha=0.8,
             label=r"Quadratic Fit $\sigma(K) = a K^2 + b K + c$")

    for q_item in quotes:
        s = q_item["strike"]
        v = q_item["deAmSigmaHat"] * 100
        cw = q_item.get("callWeight", 1.0 if q_item.get("eta") == 1 else 0.0)
        if 0.0 < cw < 1.0:
            ax1.scatter(s, v, color="#9467bd", marker="D", s=65, zorder=6,
                        edgecolor="black", linewidth=0.7,
                        label=r"Blended CRR $\hat{\sigma}_K$ ($\pm \$2$ ATM Window)")
        elif cw >= 1.0:
            ax1.scatter(s, v, color="#2ca02c", marker="^", s=55, zorder=5, label=r"CRR $\hat{\sigma}_K$ (OTM Call)")
        else:
            ax1.scatter(s, v, color="#d62728", marker="v", s=55, zorder=5, label=r"CRR $\hat{\sigma}_K$ (OTM Put)")

    # Overlay exchange IV for comparison
    if np.any(exch_vols > 0):
        ax1.plot(strikes, exch_vols, color="#7f7f7f", linestyle=":", lw=1.3, alpha=0.7, label="Exchange Quoted IV")

    ax1.axvspan(blend_low, blend_high, color="#9467bd", alpha=0.10, label=rf"Blend Window $[{blend_low:.1f}, {blend_high:.1f}]$")
    ax1.axvline(S0, color="gray", linestyle="--", alpha=0.7, label=f"Spot = ${S0:.2f}")

    handles, labels = ax1.get_legend_handles_labels()
    by_label = dict(zip(labels, handles))
    ax1.legend(by_label.values(), by_label.keys(), loc="upper right", framealpha=0.9, fontsize=8.0)

    ax1.set_title("1. Real SPY CRR De-Americanized Volatility & 5-Parameter SVI Fit", fontweight="bold", fontsize=11)
    ax1.set_xlabel("Strike ($K$)")
    ax1.set_ylabel("Implied Volatility (%)")
    ax1.set_xlim(strikes.min() - 5, strikes.max() + 5)

    # -------------------------------------------------------------
    # Panel 2: Synthetic European Call & Put Price Curves
    # -------------------------------------------------------------
    ax2 = axes[0, 1]
    call_k = [p["strike"] for p in euro_calls]
    call_px = [p["price"] for p in euro_calls]
    put_k = [p["strike"] for p in euro_puts]
    put_px = [p["price"] for p in euro_puts]

    ax2.plot(call_k, call_px, color="#1f77b4", lw=2, label=r"Synthetic Euro Call $C_{BS}(K, \sigma_{SVI}(K))$")
    ax2.plot(put_k, put_px, color="#ff7f0e", lw=2, label=r"Synthetic Euro Put $P_{BS}(K, \sigma_{SVI}(K))$")

    for q_item in quotes:
        s = q_item["strike"]
        mid = q_item["mid"]
        cw = q_item.get("callWeight", 1.0 if q_item.get("eta") == 1 else 0.0)
        if 0.0 < cw < 1.0:
            ax2.scatter(s, mid, color="#9467bd", marker="D", s=45, zorder=6, alpha=0.9, edgecolor="black", linewidth=0.5)
        elif cw >= 1.0:
            ax2.scatter(s, mid, color="#2ca02c", marker="o", s=35, zorder=5, alpha=0.8)
        else:
            ax2.scatter(s, mid, color="#d62728", marker="o", s=35, zorder=5, alpha=0.8)

    ax2.axvspan(blend_low, blend_high, color="#9467bd", alpha=0.10)
    ax2.axvline(S0, color="gray", linestyle="--", alpha=0.7)
    ax2.set_title("2. Synthetic European Price Curves vs Real SPY Quotes", fontweight="bold", fontsize=11)
    ax2.set_xlabel("Strike ($K$)")
    ax2.set_ylabel("Option Price ($)")
    ax2.set_xlim(strikes.min() - 5, strikes.max() + 5)
    ax2.legend(loc="center right", framealpha=0.9, fontsize=9)

    # -------------------------------------------------------------
    # Panel 3: Real SPY Early Exercise Premium (CRR American vs CRR European Tree)
    # -------------------------------------------------------------
    ax3 = axes[1, 0]
    strikes_list = []
    premia = []
    bar_colors = []

    for q_item in quotes:
        K = q_item["strike"]
        # Compute V^Euro via CRR Tree with early exercise turned off: V^Am - V^Euro_CRR
        premium = q_item.get("earlyExercisePremium", q_item["mid"] - q_item.get("euroCrrPrice", 0.0))
        cw = q_item.get("callWeight", 1.0 if q_item.get("eta") == 1 else 0.0)
        strikes_list.append(K)
        premia.append(premium)
        if 0.0 < cw < 1.0:
            bar_colors.append("#e377c2")
        elif cw >= 1.0:
            bar_colors.append("#2ca02c")
        else:
            bar_colors.append("#9467bd")

    ax3.bar(strikes_list, premia, width=1.4, color=bar_colors, alpha=0.85, edgecolor="black", linewidth=0.5,
            label=r"Tree Early Exercise Premium ($V^{Am}_{Tree} - V^{Euro}_{CRR}$)")
    ax3.axvspan(blend_low, blend_high, color="#9467bd", alpha=0.10, label=rf"Blend Window $[{blend_low:.1f}, {blend_high:.1f}]$")
    ax3.axhline(0, color="black", lw=1)
    ax3.axvline(S0, color="gray", linestyle="--", alpha=0.7, label=rf"$S_{{implied}} = \${S0:.2f}$")
    ax3.set_title(r"3. Early Exercise Premium via CRR Tree ($V^{Am} - V^{Euro}_{CRR}$)", fontweight="bold", fontsize=11)
    ax3.set_xlabel("Strike ($K$)")
    ax3.set_ylabel("Early Exercise Premium ($)")
    ax3.set_xlim(strikes.min() - 5, strikes.max() + 5)
    ax3.legend(loc="upper right", framealpha=0.9, fontsize=9)

    # -------------------------------------------------------------
    # Panel 4: Breeden-Litzenberger Risk-Neutral Density f_Q(K) = e^(rT) * d^2C/dK^2
    # -------------------------------------------------------------
    ax4 = axes[1, 1]
    bl_data = data.get("breedenLitzenberger", [])
    if bl_data:
        bl_k = np.array([p["strike"] for p in bl_data])
        bl_pdf = np.array([p["density"] for p in bl_data])
        bl_cdf = np.array([p["cdf"] for p in bl_data])
        forward = data.get("forwardPrice", S0 * np.exp((r - q) * T))
        total_prob = data.get("blTotalProb", 1.0)
        expected_st = data.get("blExpectedST", forward)

        # Plot density with soft area fill
        ax4.plot(bl_k, bl_pdf, color="#1f77b4", lw=2.4, zorder=4,
                 label=rf"$f_{{\mathbb{{Q}}}}(K) = e^{{rT}} \frac{{\partial^2 C}}{{\partial K^2}}$")
        ax4.fill_between(bl_k, bl_pdf, color="#1f77b4", alpha=0.22,
                         label=rf"Risk-Neutral Mass ($\int f_{{\mathbb{{Q}}}} dK \approx {total_prob:.3f}$)")

        # Vertical reference lines for Spot and Forward
        ax4.axvline(S0, color="gray", linestyle="--", alpha=0.75, label=rf"Spot $S_0 = \${S0:.2f}$")
        ax4.axvline(forward, color="#2ca02c", linestyle="-.", lw=1.6, alpha=0.85,
                    label=rf"Forward $F = \${forward:.2f}$ ($\mathbb{{E}}^{{\mathbb{{Q}}}}[S_T]=\${expected_st:.2f}$)")

        # Peak mode of the distribution
        peak_idx = np.argmax(bl_pdf)
        peak_k = bl_k[peak_idx]
        ax4.scatter([peak_k], [bl_pdf[peak_idx]], color="#d62728", s=45, zorder=5,
                    label=rf"Distribution Mode = ${peak_k:.1f}$")

        ax4.set_title(r"4. Risk-Neutral Density $f_{\mathbb{Q}}(K) = e^{rT} \frac{\partial^2 C}{\partial K^2}$ (Breeden-Litzenberger)",
                      fontweight="bold", fontsize=11)
        ax4.set_xlabel("Strike ($K$)")
        ax4.set_ylabel(r"Probability Density $f_{\mathbb{Q}}(K)$")
        ax4.set_ylim(bottom=0.0)

        # Overlay CDF on twin axis
        ax4_twin = ax4.twinx()
        ax4_twin.plot(bl_k, bl_cdf, color="#ff7f0e", linestyle=":", lw=1.6, alpha=0.85, label=r"CDF $F_{\mathbb{Q}}(K) = \mathbb{Q}(S_T \leq K)$")
        ax4_twin.set_ylabel(r"Cumulative Probability $F_{\mathbb{Q}}(K)$", color="#ff7f0e", fontsize=9.5)
        ax4_twin.set_ylim(-0.02, 1.05)
        ax4_twin.grid(False)

        # Merge legends from both axes
        h1, l1 = ax4.get_legend_handles_labels()
        h2, l2 = ax4_twin.get_legend_handles_labels()
        ax4.legend(h1 + h2, l1 + l2, loc="upper right", framealpha=0.9, fontsize=8.0)
    else:
        ax4.set_title("4. Breeden-Litzenberger Density (Data Unavailable)", fontweight="bold", fontsize=11)

    plt.tight_layout()

    # On Windows, if the file is currently open in Windows Photos or another viewer,
    # builtins.open(filename, 'w+b') raises OSError [Errno 22] or PermissionError.
    # Write to a temporary file first, then attempt atomic or fallback replace.
    tmp_png = output_png + ".tmp.png"
    try:
        plt.savefig(tmp_png, dpi=180)
        plt.close(fig)
        if os.path.exists(output_png):
            try:
                os.remove(output_png)
            except OSError:
                pass
        try:
            os.replace(tmp_png, output_png)
        except OSError:
            # If still locked by viewer, save with timestamp or alternate name
            alt_png = output_png.replace(".png", "_latest.png")
            if os.path.exists(alt_png):
                try:
                    os.remove(alt_png)
                except OSError:
                    pass
            os.replace(tmp_png, alt_png)
            output_png = alt_png
    except Exception as e:
        plt.close(fig)
        raise e

    print(f"Saved visualization to {os.path.abspath(output_png)}")

    artifact_dir = r"C:\Users\USER\.gemini\antigravity-ide\brain\040c6a35-8e7b-45b7-8912-f09b47a64fbe"
    if os.path.exists(artifact_dir):
        dest = os.path.join(artifact_dir, "surface_exploration.png")
        shutil.copyfile(output_png, dest)
        print(f"Copied visualization to artifact directory: {dest}")

def main():
    parser = argparse.ArgumentParser(description="Plot 4-panel surface exploration from options input slice")
    parser.add_argument("--input-file", default=None, help="Path to input option slice JSON (e.g. spy_t2.json or spy_input.json)")
    parser.add_argument("--output-png", default="surface_exploration.png", help="Path to output PNG image")
    args = parser.parse_args()

    if args.input_file:
        with open(args.input_file, "r", encoding="utf-8") as f:
            slice_data = json.load(f)
        output_json = os.path.join(PROJECT_ROOT, "target", "surface_data.json")
        surface_results = run_java_surface_pipeline(slice_data, args.input_file, output_json)
        plot_surface(surface_results, args.output_png)
    else:
        sample_input = os.path.join(PROJECT_ROOT, "data", "spy_input.json")
        if os.path.exists(sample_input):
            with open(sample_input, "r", encoding="utf-8") as f:
                slice_data = json.load(f)
            output_json = os.path.join(PROJECT_ROOT, "target", "surface_data.json")
            surface_results = run_java_surface_pipeline(slice_data, sample_input, output_json)
            plot_surface(surface_results, args.output_png)
        else:
            parquet_path = "spy_eod_2023.parquet"
            slice_data = extract_spy_slice(parquet_path=parquet_path, quote_date="2023-01-03", target_dte=45.0)
            surface_results = run_java_surface_pipeline(slice_data, sample_input, os.path.join(PROJECT_ROOT, "target", "surface_data.json"))
            plot_surface(surface_results, args.output_png)

if __name__ == "__main__":
    import argparse
    main()
