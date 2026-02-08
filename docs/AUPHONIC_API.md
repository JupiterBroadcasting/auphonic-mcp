# Auphonic API Research Summary

## Overview

Auphonic is an automatic audio post-production web service that provides a REST API for integrating audio processing algorithms into scripts, workflows, and applications. The API supports audio loudness normalization, intelligent leveling, noise reduction, encoding, metadata management, and automatic content deployment.

**Official Documentation:** https://auphonic.com/help/api/  
**GitHub Examples:** https://github.com/auphonic/auphonic-api-examples

## Two API Flavors

### 1. Simple API (Recommended for Scripts)
- **Endpoint:** `https://auphonic.com/api/simple/productions.json`
- **Use Case:** Quick shell scripts, batch processing
- **Advantage:** Single request to upload, process, and start production
- **Method:** Multipart/form-data POST request
- **Best for:** Referencing existing presets without detailed configuration

### 2. JSON API (Full Control)
- **Endpoint:** `https://auphonic.com/api/productions.json`
- **Use Case:** Complex workflows, detailed control
- **Method:** Multiple requests with JSON payloads
- **Best for:** Custom configurations without presets

## Authentication Methods

### API Key Authentication (Recommended for Personal Use)
```bash
-H "Authorization: Bearer {api_key}"
```
Get your API key from: https://auphonic.com/account

### HTTP Basic Authentication
```bash
-u "username:password"
```

### OAuth 2.0 (Required for Third-Party Apps)
- Desktop/Mobile apps
- Web applications with redirect URIs
- Multi-user applications

## Core Concepts

### Productions
A "production" is a single audio processing job. It includes:
- Input file(s)
- Processing algorithms
- Output formats
- Metadata
- Publishing destinations

**UUID:** Each production has a unique 22-character identifier (e.g., `KKw7AxpLrDBQKLVnQCBtCh`)

### Presets
Presets store reusable configurations:
- Output formats and quality
- Audio algorithm settings
- Publishing destinations (outgoing services)
- Default metadata

**UUID:** Like productions, presets have unique identifiers

### External Services
Connected storage/publishing platforms:
- Dropbox, Google Drive, OneDrive
- Amazon S3, SFTP, FTP
- YouTube, SoundCloud, Libsyn, Blubrry
- Many podcast hosting platforms

**UUID:** Each connected service has a unique identifier

## Simple API Examples

### Basic Upload and Process
```bash
curl -X POST https://auphonic.com/api/simple/productions.json \
  -H "Authorization: Bearer {api_key}" \
  -F "preset=ceigtvDv8jH6NaK52Z5eXH" \
  -F "title=My Production" \
  -F "input_file=@/path/to/audio.mp3" \
  -F "action=start"
```

### With Metadata
```bash
curl -X POST https://auphonic.com/api/simple/productions.json \
  -H "Authorization: Bearer {api_key}" \
  -F "preset=ceigtvDv8jH6NaK52Z5eXH" \
  -F "title=Episode 42" \
  -F "artist=Podcast Name" \
  -F "subtitle=Episode Subtitle" \
  -F "summary=Episode description..." \
  -F "image=@/path/to/cover.jpg" \
  -F "input_file=@/path/to/audio.mp3" \
  -F "action=start"
```

### From HTTP URL
```bash
curl -X POST https://auphonic.com/api/simple/productions.json \
  -H "Authorization: Bearer {api_key}" \
  -F "preset=ceigtvDv8jH6NaK52Z5eXH" \
  -F "title=Remote File Production" \
  -F "input_file=https://example.com/audio.mp3" \
  -F "action=start"
```

### From External Service (Dropbox, S3, etc.)
```bash
curl -X POST https://auphonic.com/api/simple/productions.json \
  -H "Authorization: Bearer {api_key}" \
  -F "preset=ceigtvDv8jH6NaK52Z5eXH" \
  -F "service=pmefeNCzkyT4TbRbDmoCDf" \
  -F "input_file=my_dropbox_file.mp3" \
  -F "title=Dropbox Production" \
  -F "action=start"
```

### With Chapters
```bash
curl -X POST https://auphonic.com/api/simple/productions.json \
  -H "Authorization: Bearer {api_key}" \
  -F "preset=ceigtvDv8jH6NaK52Z5eXH" \
  -F "title=Production with Chapters" \
  -F "input_file=@/path/to/audio.mp3" \
  -F "chapters=@/path/to/chapters.txt" \
  -F "action=start"
```

Chapter file format:
```
00:00:00.000 Intro <http://example.com>
00:05:30.000 Main Topic
00:15:45.000 Q&A
```

## JSON API Workflow

### Step 1: Create Production
```bash
curl -X POST -H "Content-Type: application/json" \
  https://auphonic.com/api/productions.json \
  -H "Authorization: Bearer {api_key}" \
  -d '{
    "preset": "ceigtvDv8jH6NaK52Z5eXH",
    "metadata": {
      "title": "My Production"
    }
  }'
```

Response includes production UUID:
```json
{
  "status_code": 200,
  "data": {
    "uuid": "KKw7AxpLrDBQKLVnQCBtCh",
    ...
  }
}
```

### Step 2: Upload File (Optional - if local file)
```bash
curl -X POST \
  https://auphonic.com/api/production/KKw7AxpLrDBQKLVnQCBtCh/upload.json \
  -H "Authorization: Bearer {api_key}" \
  -F "input_file=@/path/to/audio.mp3"
```

### Step 3: Start Processing
```bash
curl -X POST \
  https://auphonic.com/api/production/KKw7AxpLrDBQKLVnQCBtCh/start.json \
  -H "Authorization: Bearer {api_key}"
```

## Querying Data

### Get Production Details
```bash
curl https://auphonic.com/api/production/{uuid}.json \
  -H "Authorization: Bearer {api_key}"
```

Response includes:
- `status`: Status code (0-3)
- `status_string`: "Waiting", "Error", "Done", etc.
- `length`: Duration in seconds
- `chapters`: Array of chapter marks
- `output_files`: Array of generated files with download URLs
- `waveform_image`: URL to waveform visualization
- `outgoing_services`: Publishing status

### List All Productions
```bash
curl https://auphonic.com/api/productions.json \
  -H "Authorization: Bearer {api_key}"
```

### List Presets
```bash
curl https://auphonic.com/api/presets.json \
  -H "Authorization: Bearer {api_key}"
```

### List External Services
```bash
curl https://auphonic.com/api/services.json \
  -H "Authorization: Bearer {api_key}"
```

### Query Available Algorithms
```bash
curl https://auphonic.com/api/info/algorithms.json
```

### Query Output Formats
```bash
curl https://auphonic.com/api/info/output_file_formats.json
```

### Query Production Status Codes
```bash
curl https://auphonic.com/api/info/production_status.json
```

## Production Status Codes

- `0`: Waiting (not started)
- `1`: Processing
- `2`: Error
- `3`: Done

## Webhooks

### Set Webhook on Production
```bash
curl -H "Content-Type: application/json" -X POST \
  https://auphonic.com/api/production/{uuid}.json \
  -H "Authorization: Bearer {api_key}" \
  -d '{"webhook": "https://your-server.com/callback"}'
```

### Webhook Payload (when production finishes)
```
POST /callback HTTP/1.1
Content-Type: multipart/form-data

uuid=zeigtvDv8jH6NaK52Z5eXH
status=3
status_string=Done
```

Use the UUID to query full production details after receiving webhook.

## Downloading Result Files

After production is done, query production details to get download URLs:

```bash
curl https://auphonic.com/api/production/{uuid}.json \
  -H "Authorization: Bearer {api_key}"
```

Response includes `output_files` array:
```json
{
  "data": {
    "output_files": [
      {
        "format": "mp3",
        "ending": "mp3",
        "download_url": "https://auphonic.com/api/download/audio-result/...",
        "size": 5242880,
        "bitrate": 128
      }
    ]
  }
}
```

Download file:
```bash
curl "https://auphonic.com/api/download/audio-result/{file_id}/{filename}.mp3" \
  -H "Authorization: Bearer {api_key}" \
  -o output.mp3
```

Or use bearer token in URL for streaming:
```bash
curl "https://auphonic.com/api/download/audio-result/{file_id}/{filename}.mp3?bearer_token={api_key}"
```

## Audio Algorithm Parameters

Common parameters:
- `filtering`: High-pass filter for speech (true/false)
- `normloudness`: Global loudness normalization (true/false)
- `loudnesstarget`: Target loudness in LUFS (e.g., -16, -18, -23)
- `denoise`: Noise reduction (true/false)
- `denoiseamount`: Amount of noise reduction (0-max)
- `hipfilter`: Enable high-pass filter (true/false)
- `leveler`: Speech/music leveler (true/false)

Example:
```bash
curl -X POST https://auphonic.com/api/simple/productions.json \
  -H "Authorization: Bearer {api_key}" \
  -F "preset=ceigtvDv8jH6NaK52Z5eXH" \
  -F "title=Custom Processing" \
  -F "input_file=@/path/to/audio.mp3" \
  -F "loudnesstarget=-16" \
  -F "denoise=true" \
  -F "action=start"
```

## Output Files Configuration

You can specify output formats without a preset:

```bash
curl -X POST -H "Content-Type: application/json" \
  https://auphonic.com/api/productions.json \
  -H "Authorization: Bearer {api_key}" \
  -d '{
    "metadata": {"title": "Custom Output"},
    "output_files": [
      {"format": "mp3", "bitrate": "128"},
      {"format": "aac", "bitrate": "64", "ending": "m4a"},
      {"format": "opus", "bitrate": "64"}
    ]
  }'
```

Supported formats: mp3, aac, opus, ogg, flac, wav, m4a, and more

## Multitrack Productions

For podcast recordings with multiple speakers:

```bash
curl -X POST -H "Content-Type: application/json" \
  https://auphonic.com/api/productions.json \
  -H "Authorization: Bearer {api_key}" \
  -d '{
    "multi_input_files": [
      {"type": "multitrack", "id": "host"},
      {"type": "multitrack", "id": "guest"}
    ],
    "metadata": {"title": "Multitrack Production"},
    "output_files": [{"format": "mp3"}],
    "is_multitrack": true
  }'
```

Upload files for each track:
```bash
curl -X POST \
  https://auphonic.com/api/production/{uuid}/upload.json \
  -H "Authorization: Bearer {api_key}" \
  -F "host=@/path/to/host-audio.wav" \
  -F "guest=@/path/to/guest-audio.wav"
```

## Publishing to External Services

Specify outgoing services in production:

```bash
curl -H "Content-Type: application/json" -X POST \
  https://auphonic.com/api/production/{uuid}.json \
  -H "Authorization: Bearer {api_key}" \
  -d '{
    "outgoing_services": [
      {
        "uuid": "gxr975w3MzRS9ywnWsu6tL",
        "downloadable": true,
        "sharing": "public"
      }
    ]
  }'
```

## Common Workflows

### Batch Processing Multiple Files
```bash
#!/bin/bash
PRESET="your-preset-uuid"
API_KEY="your-api-key"

for file in *.mp3; do
  curl -X POST https://auphonic.com/api/simple/productions.json \
    -H "Authorization: Bearer $API_KEY" \
    -F "preset=$PRESET" \
    -F "title=$file" \
    -F "input_file=@$file" \
    -F "action=start"
done
```

### Process and Download
```bash
#!/bin/bash
# 1. Upload and start
RESPONSE=$(curl -X POST https://auphonic.com/api/simple/productions.json \
  -H "Authorization: Bearer $API_KEY" \
  -F "preset=$PRESET" \
  -F "input_file=@audio.mp3" \
  -F "action=start")

UUID=$(echo $RESPONSE | jq -r '.data.uuid')

# 2. Wait for completion (polling)
while true; do
  STATUS=$(curl -s https://auphonic.com/api/production/$UUID.json \
    -H "Authorization: Bearer $API_KEY" | jq -r '.data.status')
  
  if [ "$STATUS" = "3" ]; then
    echo "Done!"
    break
  elif [ "$STATUS" = "2" ]; then
    echo "Error!"
    exit 1
  fi
  
  sleep 10
done

# 3. Download result
DOWNLOAD_URL=$(curl -s https://auphonic.com/api/production/$UUID.json \
  -H "Authorization: Bearer $API_KEY" | jq -r '.data.output_files[0].download_url')

curl "$DOWNLOAD_URL" -H "Authorization: Bearer $API_KEY" -o result.mp3
```

### Using Webhooks (Better than Polling)
```bash
# Start production with webhook
curl -X POST https://auphonic.com/api/simple/productions.json \
  -H "Authorization: Bearer $API_KEY" \
  -F "preset=$PRESET" \
  -F "input_file=@audio.mp3" \
  -F "webhook=https://your-server.com/callback" \
  -F "action=start"

# Your server receives POST when done:
# uuid=xyz&status=3&status_string=Done
# Then query production details and download
```

## Important Notes

1. **Use Webhooks, Not Polling:** For production status, use webhooks instead of frequent polling
2. **Preset UUIDs:** Find preset UUIDs on the Auphonic preset page
3. **Service UUIDs:** Get external service UUIDs from `/api/services.json`
4. **Rate Limits:** Not explicitly documented, but use reasonable request rates
5. **File Size Limits:** Depend on your account plan
6. **Credits:** Processing consumes credits based on audio length and features used
7. **Multipart vs JSON:** File uploads use multipart/form-data, configuration uses application/json
8. **Content-Type Header:** Critical for JSON API requests

## Error Handling

Responses include error information:
```json
{
  "status_code": 400,
  "error_code": "invalid_preset",
  "error_message": "Preset not found",
  "form_errors": {
    "preset": ["Invalid preset UUID"]
  }
}
```

HTTP status codes:
- 200: Success
- 400: Bad request
- 401: Authentication failed
- 404: Resource not found
- 500: Server error

## Resources

- **Main API Docs:** https://auphonic.com/help/api/
- **Simple API:** https://auphonic.com/help/api/simple_api.html
- **JSON API:** https://auphonic.com/help/api/complex.html
- **Query Data:** https://auphonic.com/help/api/query.html
- **Authentication:** https://auphonic.com/help/api/authentication.html
- **Webhooks:** https://auphonic.com/help/api/webhook.html
- **GitHub Examples:** https://github.com/auphonic/auphonic-api-examples
- **Get API Key:** https://auphonic.com/account
- **Pricing/Plans:** https://auphonic.com/pricing

## Example Use Cases for Podcast Workflows

1. **Automated Episode Processing:**
   - Upload from Dropbox/S3
   - Apply loudness normalization
   - Add chapters and metadata
   - Publish to hosting platform

2. **Multitrack Recording Cleanup:**
   - Upload separate host/guest tracks
   - Auto-level speakers
   - Noise reduction per track
   - Mix to stereo output

3. **Batch Archive Processing:**
   - Loop through folder of old episodes
   - Apply modern loudness standards
   - Re-encode to efficient formats
   - Archive to S3

4. **Live Recording Pipeline:**
   - Watch folder monitors recording directory
   - New file triggers production
   - Webhook notifies when ready
   - Auto-publish to podcast host
