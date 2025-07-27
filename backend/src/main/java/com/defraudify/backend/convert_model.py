# convert_model.py
from transformers import AutoModelForSequenceClassification # Adjust if your model type is different
import os

# --- CHANGE THIS PATH ---
# Replace the path below with the actual path to YOUR exported_model directory
# Make sure to use raw string (r"") or double backslashes (\\) for Windows paths
model_path = r"C:\Users\rrver\DeFraudify\backend\src\main\resources\exported_model"
# --- END CHANGE ---

print(f"Loading model from: {model_path}")

# Load the model from the safetensors format
# trust_remote_code=True might be needed if you used custom model code during fine-tuning
model = AutoModelForSequenceClassification.from_pretrained(model_path, trust_remote_code=True)

print("Model loaded successfully.")

# Save the model in the traditional PyTorch .bin format
# safe_serialization=False forces saving as pytorch_model.bin
model.save_pretrained(model_path, safe_serialization=False)

print(f"Conversion complete. pytorch_model.bin should now be in: {model_path}")

# Optional: List files in the directory to confirm
print("\nFiles in the directory now:")
for filename in os.listdir(model_path):
    print(filename)