import pandas as pd
from rdkit import Chem
from rdkit.Chem import AllChem
import sys

datasets = {
    "herg": ("hERG cardiotoxicity blocker", "hERG"),
    "ames": ("Ames mutagenicity", "AMES"),
    "dili": ("Drug-induced liver injury", "DILI"),
    "skin_reaction": ("Skin sensitization reaction", "SkinReaction"),
    "carcinogens_lagunin": ("Carcinogenicity (Lagunin)", "Carcinogen"),
    "clintox": ("Clinical trial toxicity failure", "ClinToxic"),
    "herg_karim": ("hERG blocker (Karim, large)", "hERG"),
}

summary = []
for name, (desc, tag) in datasets.items():
    df = pd.read_pickle(f"{name}.pkl")
    total = len(df)
    ok = 0
    fail = 0
    out = f"{name}.sdf"
    with open(out, "w") as fh:
        for _, row in df.iterrows():
            smiles = row["Drug"]
            drug_id = str(row["Drug_ID"])[:200]
            y = row["Y"]
            mol = Chem.MolFromSmiles(smiles) if isinstance(smiles, str) else None
            if mol is None:
                fail += 1
                continue
            try:
                AllChem.Compute2DCoords(mol)
            except Exception:
                fail += 1
                continue
            molblock = Chem.MolToMolBlock(mol)
            fh.write(molblock)
            if not molblock.endswith("\n"):
                fh.write("\n")
            fh.write(f"> <Identifier>\n{drug_id}\n\n")
            if pd.notna(y):
                label = int(y) if float(y).is_integer() else y
                fh.write(f"> <{tag}>\n{label}\n\n")
            fh.write("$$$$\n")
            ok += 1
    summary.append((name, desc, total, ok, fail))
    print(f"{name:20s} total={total:6d} ok={ok:6d} fail={fail:4d}")

print()
print("done")
