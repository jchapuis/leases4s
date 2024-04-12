# Python example
# Generate an image using prompt
import requests
import json

api_key = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpYXQiOjE3MDU0MjE1OTIsInVzZXJfaWQiOiI2NWE2YWIxNDk1ZmU2M2U3ZjRhZDk2MGIifQ.oFChRfWR73LyL8kaQmWZIiaTRVvLf4i4qBrLpvqQgkc"

url = "https://api.wizmodel.com/sdapi/v1/txt2img"

payload = json.dumps({
  "prompt": "puppy dog running on grass",
  "steps": 100
})

headers = {
  'Content-Type': 'application/json',
  'Authorization': 'Bearer '+api_key
}

response = requests.request("POST", url, headers=headers, data=payload)

print(response.json())
