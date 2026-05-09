#!/usr/bin/env bash
set -euo pipefail

# --- Configuration ---
SSO_URL="https://sso.buaa.edu.cn"
BHPAN_URL="https://bhpan.buaa.edu.cn"
COOKIE_JAR="$(mktemp)"
TMP_DIR="$(mktemp -d)"
ARTIFACT_DIR="${1:?Usage: $0 <artifact-directory>}"
SMALL_UPLOAD_MAX_SIZE=$((100 * 1024 * 1024))
PART_SIZE=$((20 * 1024 * 1024))

cleanup() {
  rm -f "$COOKIE_JAR"
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

# --- Helpers ---
get_token() {
  grep "client.oauth2_token" "$COOKIE_JAR" 2>/dev/null | awk '{print $NF}' | tr -d '\r'
}

api_call() {
  local method="$1" endpoint="$2"
  shift 2
  local token
  token=$(get_token)
  curl -sSf --connect-timeout 30 --max-time 60 \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -X "$method" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${token}" \
    "$@" \
    "${BHPAN_URL}${endpoint}"
}

api_call_to_file() {
  local method="$1" endpoint="$2" outfile="$3"
  shift 3
  local token
  token=$(get_token)
  curl -sSf --connect-timeout 30 --max-time 60 \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -X "$method" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${token}" \
    -o "$outfile" \
    "$@" \
    "${BHPAN_URL}${endpoint}"
}

build_header_flags() {
  local response_json="$1" path="$2"
  local -n out_headers="$3"
  out_headers=()
  local header_count
  header_count=$(jq "${path} | length" <<< "$response_json")
  for ((i = 2; i < header_count; i++)); do
    local hdr
    hdr=$(jq -r "${path}[${i}]" <<< "$response_json" | tr -d '\r')
    out_headers+=(-H "$hdr")
  done
}

verify_uploaded_file() {
  local filename="$1"
  local attempt
  for attempt in 1 2 3 4 5; do
    local listing
    listing=$(list_dir "$BHPAN_SHARED_FOLDER_DOCID")
    if jq -e --arg name "$filename" '.files[]? | select(.name == $name)' <<< "$listing" > /dev/null; then
      return 0
    fi
    sleep 2
  done

  echo "::error::Uploaded file is not visible in the target directory: ${filename}"
  return 1
}

# --- SSO Login ---
sso_login() {
  echo "Logging in to SSO..."

  # Step 1: GET login page, extract execution token
  local login_page
  login_page=$(curl -sS --connect-timeout 30 \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    "${SSO_URL}/login")

  local execution
  execution=$(echo "$login_page" | sed -n 's/.*name="execution" value="\([^"]*\)".*/\1/p' | head -1)
  if [[ -z "$execution" ]]; then
    echo "::error::Failed to extract execution token from SSO login page"
    exit 1
  fi

  # Step 2: POST login form
  curl -sS --connect-timeout 30 --max-time 60 \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -L -o /dev/null \
    -X POST "${SSO_URL}/login" \
    --data-urlencode "username=${BHPAN_SSO_USERNAME}" \
    --data-urlencode "password=${BHPAN_SSO_PASSWORD}" \
    --data-urlencode "submit=LOGIN" \
    --data-urlencode "type=username_password" \
    --data-urlencode "execution=${execution}" \
    --data-urlencode "_eventId=submit"

  # Step 3: Handle potential security warning (ignoreAndContinue)
  local has_tgc
  has_tgc=$(grep -c "TGC" "$COOKIE_JAR" 2>/dev/null || true)
  if [[ "$has_tgc" -eq 0 ]]; then
    local cont_page
    cont_page=$(curl -sS --connect-timeout 30 \
      -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
      "${SSO_URL}/login")
    execution=$(echo "$cont_page" | sed -n 's/.*name="execution" value="\([^"]*\)".*/\1/p' | head -1)
    if [[ -n "$execution" ]]; then
      curl -sS --connect-timeout 30 --max-time 60 \
        -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
        -L -o /dev/null \
        -X POST "${SSO_URL}/login" \
        --data-urlencode "username=${BHPAN_SSO_USERNAME}" \
        --data-urlencode "password=${BHPAN_SSO_PASSWORD}" \
        --data-urlencode "submit=LOGIN" \
        --data-urlencode "type=username_password" \
        --data-urlencode "execution=${execution}" \
        --data-urlencode "_eventId=ignoreAndContinue"
    fi
  fi

  echo "SSO login completed."
}

# --- Cloud Disk OAuth2 Auth ---
bhpan_auth() {
  echo "Authenticating with BUAA Cloud Disk..."

  # Hop 1: /anyshare/oauth2/login -> /oauth2/auth
  local auth_url
  auth_url=$(curl -sS --connect-timeout 30 \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -o /dev/null -w "%{redirect_url}" \
    "${BHPAN_URL}/anyshare/oauth2/login?redirect=%2Fanyshare%2Fzh-cn%2Fportal")

  if [[ -z "$auth_url" ]]; then
    echo "::error::No redirect from OAuth2 login endpoint"
    exit 1
  fi

  # Hop 2: /oauth2/auth -> /oauth2/signin?login_challenge=...
  local signin_url
  signin_url=$(curl -sS --connect-timeout 30 \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -o /dev/null -w "%{redirect_url}" \
    "$auth_url")

  local login_challenge
  login_challenge=$(echo "$signin_url" | sed -n 's/.*login_challenge=\([^&]*\).*/\1/p')

  if [[ -z "$login_challenge" ]]; then
    echo "::error::No login_challenge in redirect chain"
    exit 1
  fi

  # Set login_challenge cookie (required by bhpan)
  echo "#HttpOnly_bhpan.buaa.edu.cn	FALSE	/	TRUE	0	login_challenge	${login_challenge}" >> "$COOKIE_JAR"

  # Hop 3: SSO with service param (uses existing SSO session, redirects back to bhpan)
  local callback_url
  callback_url=$(curl -sS --connect-timeout 30 --max-time 60 \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -L -o /dev/null -w "%{url_effective}" \
    "${SSO_URL}/login?service=https%3A%2F%2Fbhpan.buaa.edu.cn%2Foauth2%2Fsignin")

  if [[ "$callback_url" != *"/login/callback"* ]]; then
    echo "::error::OAuth2 redirect did not land on callback. Got: ${callback_url}"
    exit 1
  fi

  # Verify we got the OAuth token
  if ! grep -q "client.oauth2_token" "$COOKIE_JAR" 2>/dev/null; then
    echo "::error::Failed to obtain OAuth2 token from bhpan"
    exit 1
  fi

  echo "Cloud Disk authentication completed."
}

# --- Directory Listing ---
list_dir() {
  local docid="$1"
  api_call POST "/api/efast/v1/dir/list" \
    -d "{\"by\":\"name\",\"docid\":\"${docid}\",\"sort\":\"asc\"}"
}

# --- Delete Old Files ---
delete_old_files() {
  echo "Listing files in shared folder..."
  local listing
  listing=$(list_dir "$BHPAN_SHARED_FOLDER_DOCID")

  # Extract docids of files starting with "UBAA-"
  local old_docids
  old_docids=$(echo "$listing" | jq -r '.files[] | select(.name | startswith("UBAA-")) | .docid' | tr -d '\r')

  local count
  count=$(echo "$old_docids" | grep -c '.' || true)
  echo "Found ${count} old UBAA files to delete."

  while IFS= read -r docid; do
    [[ -z "$docid" ]] && continue
    local name
    name=$(echo "$listing" | jq -r ".files[] | select(.docid == \"${docid}\") | .name" | tr -d '\r')
    echo "  Deleting: ${name}"
    api_call POST "/api/efast/v1/file/delete" \
      -d "{\"docid\":\"${docid}\"}" || echo "  Warning: failed to delete ${name}, continuing..."
  done <<< "$old_docids"

  echo "Old file cleanup completed."
}

upload_small_file() {
  local filepath="$1" filename="$2" filesize="$3"
  local client_mtime begin_payload begin_response put_url docid rev
  local -a header_flags=()

  client_mtime=$(date +%s%3N)
  begin_payload=$(jq -nc \
    --arg docid "$BHPAN_SHARED_FOLDER_DOCID" \
    --arg name "$filename" \
    --argjson client_mtime "$client_mtime" \
    --argjson length "$filesize" \
    '{"client_mtime": $client_mtime, "docid": $docid, "length": $length, "name": $name, "ondup": 1}')

  begin_response=$(api_call POST "/api/efast/v1/file/osbeginupload" -d "$begin_payload")
  put_url=$(jq -r '.authrequest[1]' <<< "$begin_response" | tr -d '\r')
  docid=$(jq -r '.docid' <<< "$begin_response" | tr -d '\r')
  rev=$(jq -r '.rev' <<< "$begin_response" | tr -d '\r')

  if [[ "$put_url" == "null" || -z "$put_url" ]]; then
    echo "::error::Failed to begin upload for ${filename}"
    echo "  Response: ${begin_response}"
    return 1
  fi

  build_header_flags "$begin_response" '.authrequest' header_flags

  local max_retries=3
  local attempt=1
  while true; do
    if curl -sS --fail-with-body --connect-timeout 30 --max-time 600 \
      -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
      -X PUT \
      "${header_flags[@]}" \
      -T "$filepath" \
      -o /dev/null \
      "$put_url"; then
      break
    fi
    if [[ $attempt -ge $max_retries ]]; then
      echo "::error::Failed to upload ${filename} after ${max_retries} attempts"
      return 1
    fi
    echo "  Retry ${attempt}/${max_retries} for ${filename}..."
    attempt=$((attempt + 1))
    sleep 5
  done

  local end_payload end_response
  end_payload=$(jq -nc --arg docid "$docid" --arg rev "$rev" '{"docid": $docid, "rev": $rev}')
  end_response=$(api_call POST "/api/efast/v1/file/osendupload" -d "$end_payload")

  if [[ -z "$end_response" ]] || jq -e '.error' <<< "$end_response" > /dev/null 2>&1; then
    echo "::error::osendupload failed for ${filename}"
    echo "  Response: ${end_response}"
    return 1
  fi

  verify_uploaded_file "$filename"
  echo "  Uploaded: ${filename}"
}

upload_big_file() {
  local filepath="$1" filename="$2" filesize="$3"
  echo "  Using multipart upload for large artifact: ${filename}"

  local init_payload init_response docid rev uploadid
  init_payload=$(jq -nc \
    --arg docid "$BHPAN_SHARED_FOLDER_DOCID" \
    --arg name "$filename" \
    --argjson length "$filesize" \
    '{"docid": $docid, "length": $length, "name": $name, "ondup": 1}')
  init_response=$(api_call POST "/api/efast/v1/file/osinitmultiupload" -d "$init_payload")

  docid=$(jq -r '.docid' <<< "$init_response" | tr -d '\r')
  rev=$(jq -r '.rev' <<< "$init_response" | tr -d '\r')
  uploadid=$(jq -r '.uploadid' <<< "$init_response" | tr -d '\r')
  if [[ -z "$docid" || "$docid" == "null" || -z "$rev" || "$rev" == "null" || -z "$uploadid" || "$uploadid" == "null" ]]; then
    echo "::error::Failed to initialize multipart upload for ${filename}"
    echo "  Response: ${init_response}"
    return 1
  fi

  local part_count
  part_count=$(( (filesize + PART_SIZE - 1) / PART_SIZE ))
  local parts_payload part_response
  parts_payload=$(jq -nc \
    --arg docid "$docid" \
    --arg rev "$rev" \
    --arg uploadid "$uploadid" \
    --arg parts "1-${part_count}" \
    '{"docid": $docid, "rev": $rev, "uploadid": $uploadid, "parts": $parts}')
  part_response=$(api_call POST "/api/efast/v1/file/osuploadpart" -d "$parts_payload")

  local part_info='{}'
  local part_number
  while IFS= read -r part_number; do
    [[ -z "$part_number" ]] && continue

    local part_file header_file part_size part_url etag
    local -a header_flags=()

    part_file="$TMP_DIR/${filename}.${part_number}.part"
    header_file="$TMP_DIR/${filename}.${part_number}.headers"
    dd if="$filepath" of="$part_file" bs="$PART_SIZE" count=1 skip=$((part_number - 1)) status=none
    part_size=$(stat -c%s "$part_file")
    part_url=$(jq -r ".authrequests[\"${part_number}\"][1]" <<< "$part_response" | tr -d '\r')

    if [[ -z "$part_url" || "$part_url" == "null" ]]; then
      echo "::error::Failed to obtain multipart upload URL for ${filename} part ${part_number}"
      echo "  Response: ${part_response}"
      return 1
    fi

    build_header_flags "$part_response" ".authrequests[\"${part_number}\"]" header_flags
    curl -sS --fail-with-body --connect-timeout 30 --max-time 600 \
      -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
      -X PUT \
      "${header_flags[@]}" \
      -H "Content-Length: ${part_size}" \
      -D "$header_file" \
      -T "$part_file" \
      -o /dev/null \
      "$part_url"

    etag=$(awk 'tolower($1) == "etag:" { print $2 }' "$header_file" | tail -n 1 | tr -d '\r"')
    if [[ -z "$etag" ]]; then
      echo "::error::Missing ETag for ${filename} part ${part_number}"
      return 1
    fi

    part_info=$(jq -nc \
      --argjson info "$part_info" \
      --arg key "$part_number" \
      --arg etag "$etag" \
      --argjson size "$part_size" \
      '$info + {($key): [$etag, $size]}')
  done < <(jq -r '.authrequests | keys[]' <<< "$part_response" | sort -n)

  local complete_payload complete_response_file complete_xml_file complete_json_file complete_json
  local complete_url
  local -a complete_header_flags=()

  complete_payload=$(jq -nc \
    --arg docid "$docid" \
    --arg rev "$rev" \
    --arg uploadid "$uploadid" \
    --argjson partinfo "$part_info" \
    '{"docid": $docid, "rev": $rev, "uploadid": $uploadid, "partinfo": $partinfo}')
  complete_response_file="$TMP_DIR/${filename}.complete.response"
  complete_xml_file="$TMP_DIR/${filename}.complete.xml"
  complete_json_file="$TMP_DIR/${filename}.complete.json"

  api_call_to_file POST "/api/efast/v1/file/oscompleteupload" "$complete_response_file" -d "$complete_payload"
  perl -0ne 'if (/<.*?(?=\r?\n--)/s) { print $& }' "$complete_response_file" > "$complete_xml_file"
  perl -0ne 'if (/\{.*?(?=\r?\n--)/s) { print $& }' "$complete_response_file" > "$complete_json_file"

  if [[ ! -s "$complete_xml_file" || ! -s "$complete_json_file" ]]; then
    echo "::error::Failed to parse multipart completion response for ${filename}"
    return 1
  fi

  complete_json=$(<"$complete_json_file")
  complete_url=$(jq -r '.authrequest[1]' <<< "$complete_json" | tr -d '\r')
  if [[ -z "$complete_url" || "$complete_url" == "null" ]]; then
    echo "::error::Failed to obtain completion URL for ${filename}"
    echo "  Response: ${complete_json}"
    return 1
  fi

  build_header_flags "$complete_json" '.authrequest' complete_header_flags
  curl -sS --fail-with-body --connect-timeout 30 --max-time 600 \
    -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -X POST \
    "${complete_header_flags[@]}" \
    --data-binary "@${complete_xml_file}" \
    -o /dev/null \
    "$complete_url"

  local end_payload end_response
  end_payload=$(jq -nc --arg docid "$docid" --arg rev "$rev" '{"docid": $docid, "rev": $rev}')
  end_response=$(api_call POST "/api/efast/v1/file/osendupload" -d "$end_payload")

  if [[ -z "$end_response" ]] || jq -e '.error' <<< "$end_response" > /dev/null 2>&1; then
    echo "::error::osendupload failed for ${filename}"
    echo "  Response: ${end_response}"
    return 1
  fi

  verify_uploaded_file "$filename"
  echo "  Uploaded: ${filename}"
}

# --- Upload Single File ---
upload_file() {
  local filepath="$1"
  local filename filesize
  filename=$(basename "$filepath")
  filesize=$(stat -c%s "$filepath")

  echo "  Uploading: ${filename} (${filesize} bytes)"
  if (( filesize > SMALL_UPLOAD_MAX_SIZE )); then
    upload_big_file "$filepath" "$filename" "$filesize"
  else
    upload_small_file "$filepath" "$filename" "$filesize"
  fi
}

# --- Upload All Artifacts ---
upload_all() {
  local count=0
  for filepath in "${ARTIFACT_DIR}"/UBAA-*; do
    [[ -f "$filepath" ]] || continue
    upload_file "$filepath"
    count=$((count + 1))
  done
  echo "Uploaded ${count} files to BUAA Cloud Disk."
}

# --- Main ---
echo "=== BUAA Cloud Disk Upload ==="
echo "Artifact directory: ${ARTIFACT_DIR}"

# Validate environment
if [[ -z "${BHPAN_SSO_USERNAME:-}" || -z "${BHPAN_SSO_PASSWORD:-}" || -z "${BHPAN_SHARED_FOLDER_DOCID:-}" ]]; then
  echo "::error::Missing required environment variables (BHPAN_SSO_USERNAME, BHPAN_SSO_PASSWORD, BHPAN_SHARED_FOLDER_DOCID)"
  exit 1
fi

# Validate artifact directory
if [[ ! -d "$ARTIFACT_DIR" ]]; then
  echo "::error::Artifact directory does not exist: ${ARTIFACT_DIR}"
  exit 1
fi

artifact_count=$(ls "${ARTIFACT_DIR}"/UBAA-* 2>/dev/null | wc -l)
if [[ "$artifact_count" -eq 0 ]]; then
  echo "::error::No UBAA-* artifacts found in ${ARTIFACT_DIR}"
  exit 1
fi
echo "Found ${artifact_count} artifacts to upload."

sso_login
bhpan_auth
delete_old_files
upload_all

echo "=== Upload complete ==="
