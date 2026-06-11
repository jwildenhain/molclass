import os
import xml.etree.ElementTree as ET

def find_config_file():
    # Traverses upwards from the script location to find the central molclass.conf.xml
    current_dir = os.path.abspath(os.path.dirname(__file__))
    for _ in range(5):
        candidate = os.path.join(current_dir, "molclass.conf.xml")
        if os.path.exists(candidate):
            return candidate
        current_dir = os.path.dirname(current_dir)
    
    # Fallback to current working directory
    if os.path.exists("molclass.conf.xml"):
        return "molclass.conf.xml"
        
    # Standard repository location fallback
    return "/home/jw/repos/wdc_gitlab/molclass/molclass.conf.xml"

class Settings:
    def __init__(self):
        config_path = find_config_file()
        if not os.path.exists(config_path):
            raise FileNotFoundError(f"Configuration file 'molclass.conf.xml' not found (searched up to 5 parent directories).")
            
        try:
            tree = ET.parse(config_path)
            root = tree.getroot()
            
            # Read database settings (API uses read-only credentials)
            self.db_host = root.find("hostname").text.strip() if root.find("hostname") is not None else "localhost"
            self.db_name = root.find("database").text.strip() if root.find("database") is not None else "molclass"
            self.db_user = root.find("ro_user").text.strip() if root.find("ro_user") is not None else "molclass_user"
            self.db_pass = root.find("ro_password").text.strip() if root.find("ro_password") is not None else ""
            
            # Additional global properties
            self.website = root.find("website").text.strip() if root.find("website") is not None else "http://localhost/molclass"
        except Exception as e:
            raise RuntimeError(f"Error parsing configuration XML file: {e}")

settings = Settings()
