#!/usr/bin/env python3
"""
MUAC Statistical Validation Script
==================================
Evaluates agreement between clinical gold-standard tape measurements (tape_mm)
and camera-estimated MUAC values (app_mm).

Metrics computed:
  1. Mean Absolute Error (MAE)
  2. Bias (Mean Difference: app - tape)
  3. Bland-Altman 95% Limits of Agreement (Mean Difference +/- 1.96 * SD)
  4. SAM (<115 mm) / MAM (115-124 mm) / Normal (>=125 mm) Classification Agreement
  5. Diagnostic Accuracy for SAM: Sensitivity and Specificity

Standard library only (csv, math, sys).
"""

import csv
import math
import sys
from typing import List, Dict, Any, Tuple


def classify_muac(val: float) -> str:
    """Classify MUAC into WHO growth standard categories."""
    if val < 115.0:
        return "SAM"
    elif val <= 124.0:
        return "MAM"
    else:
        return "NORMAL"


def load_muac_csv(file_path: str) -> List[Dict[str, Any]]:
    """Loads CSV with columns: id, tape_mm, app_mm."""
    rows = []
    with open(file_path, mode="r", encoding="utf-8-sig") as f:
        # Filter leading comment lines before passing to DictReader
        non_comment_lines = [line for line in f if line.strip() and not line.strip().startswith("#")]
        
        if not non_comment_lines:
            raise ValueError("CSV file is empty or contains only comments.")

        reader = csv.DictReader(non_comment_lines)
        if not reader.fieldnames:
            raise ValueError("CSV file is empty or missing headers.")
        
        # Normalize column header names
        col_map = {col.strip().lower(): col for col in reader.fieldnames}
        
        required = ["id", "tape_mm", "app_mm"]
        for req in required:
            if req not in col_map:
                raise ValueError(
                    f"Missing required column '{req}'. Found headers: {list(reader.fieldnames)}"
                )
        
        id_col = col_map["id"]
        tape_col = col_map["tape_mm"]
        app_col = col_map["app_mm"]

        for line_no, row in enumerate(reader, start=2):
            # Skip comments or empty rows
            raw_id = row[id_col].strip() if row[id_col] else ""
            if not raw_id or raw_id.startswith("#"):
                continue
            
            try:
                tape_val = float(row[tape_col].strip())
                app_val = float(row[app_col].strip())
            except ValueError:
                print(f"[WARN] Skipping row {line_no} due to invalid numeric values: {row}", file=sys.stderr)
                continue

            rows.append({
                "id": raw_id,
                "tape_mm": tape_val,
                "app_mm": app_val
            })
            
    if not rows:
        raise ValueError("No valid data rows found in CSV file.")
    return rows


def compute_metrics(records: List[Dict[str, Any]]) -> Dict[str, Any]:
    n = len(records)
    differences = [r["app_mm"] - r["tape_mm"] for r in records]
    abs_differences = [abs(d) for d in differences]

    # 1. MAE
    mae = sum(abs_differences) / n

    # 2. Bias (mean difference: app - tape)
    bias = sum(differences) / n

    # 3. Standard deviation of differences for Bland-Altman
    if n > 1:
        variance = sum((d - bias) ** 2 for d in differences) / (n - 1)
        sd_diff = math.sqrt(variance)
    else:
        sd_diff = 0.0

    loa_lower = bias - (1.96 * sd_diff)
    loa_upper = bias + (1.96 * sd_diff)

    # 4. Classification agreement: SAM (<115), MAM (115-124), Normal (>=125)
    exact_class_matches = 0
    confusion_matrix = {
        "SAM": {"SAM": 0, "MAM": 0, "NORMAL": 0},
        "MAM": {"SAM": 0, "MAM": 0, "NORMAL": 0},
        "NORMAL": {"SAM": 0, "MAM": 0, "NORMAL": 0},
    }

    # For SAM diagnostic performance (Reference = Tape, Test = App)
    tp_sam = 0  # tape < 115 and app < 115
    fp_sam = 0  # tape >= 115 and app < 115
    fn_sam = 0  # tape < 115 and app >= 115
    tn_sam = 0  # tape >= 115 and app >= 115

    for r in records:
        tape_class = classify_muac(r["tape_mm"])
        app_class = classify_muac(r["app_mm"])

        confusion_matrix[tape_class][app_class] += 1
        if tape_class == app_class:
            exact_class_matches += 1

        # SAM binary metrics
        is_tape_sam = (r["tape_mm"] < 115.0)
        is_app_sam = (r["app_mm"] < 115.0)

        if is_tape_sam and is_app_sam:
            tp_sam += 1
        elif not is_tape_sam and is_app_sam:
            fp_sam += 1
        elif is_tape_sam and not is_app_sam:
            fn_sam += 1
        else:
            tn_sam += 1

    overall_agreement_pct = (exact_class_matches / n) * 100.0

    # Sensitivity and Specificity for SAM
    sam_positives = tp_sam + fn_sam
    sam_negatives = tn_sam + fp_sam

    sensitivity_sam = (tp_sam / sam_positives) if sam_positives > 0 else None
    specificity_sam = (tn_sam / sam_negatives) if sam_negatives > 0 else None

    return {
        "n": n,
        "mae": mae,
        "bias": bias,
        "sd_diff": sd_diff,
        "loa_lower": loa_lower,
        "loa_upper": loa_upper,
        "exact_class_matches": exact_class_matches,
        "overall_agreement_pct": overall_agreement_pct,
        "confusion_matrix": confusion_matrix,
        "tp_sam": tp_sam,
        "fp_sam": fp_sam,
        "fn_sam": fn_sam,
        "tn_sam": tn_sam,
        "sensitivity_sam": sensitivity_sam,
        "specificity_sam": specificity_sam,
    }


def print_report(res: Dict[str, Any], file_path: str):
    print("=" * 68)
    print("            ASHA-AI: MUAC VALIDATION ANALYSIS REPORT")
    print("=" * 68)
    print(f"Data Source       : {file_path}")
    print(f"Sample Size (N)   : {res['n']} paired measurements")
    print("-" * 68)
    print("1. CONTINUOUS AGREEMENT & ERROR METRICS")
    print(f"   • Mean Absolute Error (MAE) : {res['mae']:.2f} mm")
    print(f"   • Bias (Mean Diff, App-Tape): {res['bias']:+.2f} mm (SD = {res['sd_diff']:.2f} mm)")
    print(f"   • Bland-Altman 95% LoA      : [{res['loa_lower']:+.2f} mm  to  {res['loa_upper']:+.2f} mm]")
    print("-" * 68)
    print("2. WHO CATEGORY CLASSIFICATION AGREEMENT")
    print(f"   Categories: SAM (<115mm) | MAM (115-124mm) | Normal (>=125mm)")
    print(f"   • Exact Category Agreement  : {res['exact_class_matches']}/{res['n']} ({res['overall_agreement_pct']:.1f}%)")
    print()
    print("   Confusion Matrix (Rows: Tape Gold-Standard / Columns: App Prediction):")
    print(f"   {'Tape \\ App':<12} | {'SAM (<115)':<12} | {'MAM (115-124)':<14} | {'Normal (>=125)':<14}")
    print("   " + "-" * 58)
    for row_cat in ["SAM", "MAM", "NORMAL"]:
        cells = res["confusion_matrix"][row_cat]
        print(f"   {row_cat:<12} | {cells['SAM']:<12} | {cells['MAM']:<14} | {cells['NORMAL']:<14}")
    print("-" * 68)
    print("3. DIAGNOSTIC SCREENING PERFORMANCE FOR SAM (<115 mm)")
    print(f"   • True Positives (TP) : {res['tp_sam']:<4} | False Positives (FP) : {res['fp_sam']:<4}")
    print(f"   • False Negatives (FN): {res['fn_sam']:<4} | True Negatives (TN)  : {res['tn_sam']:<4}")

    sens_str = f"{res['sensitivity_sam'] * 100:.1f}%" if res['sensitivity_sam'] is not None else "N/A (no positives)"
    spec_str = f"{res['specificity_sam'] * 100:.1f}%" if res['specificity_sam'] is not None else "N/A (no negatives)"

    print(f"   • SAM Sensitivity     : {sens_str} (Detection of acute malnutrition)")
    print(f"   • SAM Specificity     : {spec_str} (Avoidance of false alarm referral)")
    print("=" * 68)
    print("Note: AI screening estimates do not constitute clinical diagnosis.")
    print("All children flagged with SAM/MAM must be verified by ASHA using physical tape.")
    print("=" * 68)


def main():
    if len(sys.argv) < 2:
        print("Usage: python validate_muac.py <path_to_muac_csv>")
        print("Example: python validate_muac.py scripts/sample_muac_validation.csv")
        sys.exit(1)

    file_path = sys.argv[1]
    try:
        records = load_muac_csv(file_path)
        metrics = compute_metrics(records)
        print_report(metrics, file_path)
    except Exception as e:
        print(f"Error executing validation: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
