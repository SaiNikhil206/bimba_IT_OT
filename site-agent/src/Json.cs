using System.Collections;
using System.Collections.Generic;
using System.Text;
using System.Web.Script.Serialization;

namespace Bimba.SiteAgent
{
    internal static class Json
    {
        private static JavaScriptSerializer New() => new JavaScriptSerializer { MaxJsonLength = 4 * 1024 * 1024 };

        public static string Serialize(object o) => New().Serialize(o);

        /// <summary>Parses a JSON object; returns an empty dictionary for anything else.</summary>
        public static Dictionary<string, object> Parse(string json)
        {
            try
            {
                return New().DeserializeObject(json) as Dictionary<string, object> ?? new Dictionary<string, object>();
            }
            catch
            {
                return new Dictionary<string, object>();
            }
        }

        public static string Str(IDictionary<string, object> o, string key) =>
            o != null && o.TryGetValue(key, out var v) && v != null ? v.ToString() : null;

        public static int Int(IDictionary<string, object> o, string key, int fallback) =>
            o != null && o.TryGetValue(key, out var v) && v is int i ? i : fallback;

        public static IDictionary<string, object> Obj(IDictionary<string, object> o, string key) =>
            o != null && o.TryGetValue(key, out var v) ? v as IDictionary<string, object> : null;

        /// <summary>Indented JSON for files people may open (agent.json, "inventory" output).</summary>
        public static string Pretty(object o)
        {
            var sb = new StringBuilder();
            Write(sb, o, 0);
            return sb.ToString();
        }

        private static void Write(StringBuilder sb, object o, int depth)
        {
            string pad = new string(' ', (depth + 1) * 2);
            string end = new string(' ', depth * 2);
            switch (o)
            {
                case IDictionary<string, object> map:
                    if (map.Count == 0) { sb.Append("{}"); return; }
                    sb.Append("{\n");
                    int i = 0;
                    foreach (var kv in map)
                    {
                        sb.Append(pad).Append(Serialize(kv.Key)).Append(": ");
                        Write(sb, kv.Value, depth + 1);
                        sb.Append(++i < map.Count ? ",\n" : "\n");
                    }
                    sb.Append(end).Append('}');
                    return;
                case string _:
                    sb.Append(Serialize(o));
                    return;
                case IEnumerable list:
                    var items = new List<object>();
                    foreach (var x in list) items.Add(x);
                    if (items.Count == 0) { sb.Append("[]"); return; }
                    sb.Append("[\n");
                    for (int j = 0; j < items.Count; j++)
                    {
                        sb.Append(pad);
                        Write(sb, items[j], depth + 1);
                        sb.Append(j + 1 < items.Count ? ",\n" : "\n");
                    }
                    sb.Append(end).Append(']');
                    return;
                default:
                    sb.Append(Serialize(o));
                    return;
            }
        }
    }
}
