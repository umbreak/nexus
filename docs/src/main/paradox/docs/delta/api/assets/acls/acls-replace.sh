curl -XPUT \
-H "Content-Type: application/json" "http://localhost:8080/v1/acls/org1?rev=1" -d \
'{
  "acl": [
    {
      "permissions": [
        "projects/read"
      ],
      "identity": {
        "realm": "myrealm",
        "group": "a-group"
      }
    },
    {
      "permissions": [
        "projects/read",
        "projects/write"
      ],
      "identity": {
        "realm": "realm",
        "group": "some-group"
      }
    },
    {
      "permissions": [
        "acls/read",
        "acls/write"
      ],
      "identity": {
        "realm": "realm",
        "subject": "alice"
      }
    }
  ]
}'